package coop.rchain.casper.util.comm

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.casper.ValidatorIdentity
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.genesis.contracts.{ProofOfStakeValidator, Wallet}
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.EventConverter
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.catscontrib.Capture
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.comm.CommError.ErrorHandler
import coop.rchain.comm.protocol.rchain.Packet
import coop.rchain.comm.rp.Connect.RPConfAsk
import coop.rchain.comm.transport.CommMessages.packet
import coop.rchain.comm.transport.TransportLayer
import coop.rchain.comm.{transport, PeerNode}
import coop.rchain.crypto.hash.Blake2b256
import coop.rchain.shared._
import monix.execution.Scheduler

import scala.util.Try

/**
  * Validator side of the protocol defined in
  * https://rchain.atlassian.net/wiki/spaces/CORE/pages/485556483/Initializing+the+Blockchain+--+Protocol+for+generating+the+Genesis+block
  */
class BlockApproverProtocol(validatorId: ValidatorIdentity,
                            deployTimestamp: Long,
                            runtimeManager: RuntimeManager,
                            bonds: Map[Array[Byte], Int],
                            wallets: Seq[Wallet],
                            requiredSigs: Int)(implicit scheduler: Scheduler) {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val _bonds                        = bonds.map(e => ByteString.copyFrom(e._1) -> e._2)

  def unapprovedBlockPacketHandler[
      F[_]: Capture: Monad: TransportLayer: Log: Time: ErrorHandler: RPConfAsk](
      peer: PeerNode,
      u: UnapprovedBlock): F[Option[Packet]] =
    if (u.candidate.isEmpty) {
      Log[F]
        .warn("Candidate is not defined.")
        .map(_ => none[Packet])
    } else {
      val candidate = u.candidate.get
      val validCandidate = BlockApproverProtocol.validateCandidate(runtimeManager,
                                                                   candidate,
                                                                   requiredSigs,
                                                                   deployTimestamp,
                                                                   wallets,
                                                                   _bonds)
      validCandidate match {
        case Right(_) =>
          for {
            local <- RPConfAsk[F].reader(_.local)
            serializedApproval = BlockApproverProtocol
              .getApproval(candidate, validatorId)
              .toByteArray
            msg  = packet(local, transport.BlockApproval, serializedApproval)
            send <- TransportLayer[F].send(peer, msg)
            _    <- Log[F].info(s"Received expected candidate from $peer. Approval sent in response.")
          } yield none[Packet]
        case Left(errMsg) =>
          Log[F]
            .warn(s"Received unexpected candidate from $peer because: $errMsg")
            .map(_ => none[Packet])
      }
    }
}

object BlockApproverProtocol {
  def getBlockApproval(expectedCandidate: ApprovedBlockCandidate,
                       validatorId: ValidatorIdentity): BlockApproval = {
    val sigData = Blake2b256.hash(expectedCandidate.toByteArray)
    val sig     = validatorId.signature(sigData)
    BlockApproval(Some(expectedCandidate), Some(sig))
  }

  def getApproval(candidate: ApprovedBlockCandidate,
                  validatorId: ValidatorIdentity): BlockApproval =
    getBlockApproval(candidate, validatorId)

  def validateCandidate(
      runtimeManager: RuntimeManager,
      candidate: ApprovedBlockCandidate,
      requiredSigs: Int,
      timestamp: Long,
      wallets: Seq[Wallet],
      bonds: Map[ByteString, Int])(implicit scheduler: Scheduler): Either[String, Unit] =
    for {
      _ <- (candidate.requiredSigs == requiredSigs)
            .either(())
            .or("Candidate didn't have required signatures number.")
      block      <- Either.fromOption(candidate.block, "Candidate block is empty.")
      body       <- Either.fromOption(block.body, "Body is empty")
      postState  <- Either.fromOption(body.postState, "Post state is empty")
      blockBonds = postState.bonds.map { case Bond(validator, stake) => validator -> stake }.toMap
      _ <- (blockBonds == bonds)
            .either(())
            .or("Block bonds don't match expected.")
      replayLog           = body.commReductions.map(EventConverter.toRspaceEvent).toList
      validators          = blockBonds.toSeq.map(b => ProofOfStakeValidator(b._1.toByteArray, b._2))
      genesisBlessedTerms = Genesis.defaultBlessedTerms(timestamp, validators, wallets)
      replayedState <- runtimeManager
                        .replayComputeState(replayLog)
                        .apply(runtimeManager.emptyStateHash, genesisBlessedTerms)
                        .leftMap(r =>
                          s"Errors during replay: ${r._2.map(_.getMessage).mkString(", ")}.")
      checkpoint = replayedState._1
      _ <- (ByteString.copyFrom(checkpoint.root.bytes.toArray) == postState.tuplespace)
            .either(())
            .or("Tuplespace hash mismatch.")
      tuplespaceBonds <- Try(runtimeManager.computeBonds(postState.tuplespace)).toEither
                          .leftMap(_.getMessage)
      tuplespaceBondsMap = tuplespaceBonds.map { case Bond(validator, stake) => validator -> stake }.toMap
      _ <- (tuplespaceBondsMap == bonds)
            .either(())
            .or("Tuplespace bonds don't match expected ones.")
    } yield ()

  def packetToUnapprovedBlock(msg: Packet): Option[UnapprovedBlock] =
    if (msg.typeId == transport.UnapprovedBlock.id)
      Try(UnapprovedBlock.parseFrom(msg.content.toByteArray)).toOption
    else None
}