package scorex.consensus

import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.block.{Block, BlockField, BlockProcessingModule}
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.transaction.{AssetAcc, TransactionModule}

import scala.util.Try


trait ConsensusModule {

  def parseBlockFields(blockFields: BlockField[NxtLikeConsensusBlockData]): NxtLikeConsensusBlockData = blockFields.value

  def genesisData: BlockField[NxtLikeConsensusBlockData]

  def isValid(block: Block)(implicit transactionModule: TransactionModule): Boolean

  /**
    * Get block producers(miners/forgers). Usually one miner produces a block, but in some proposals not
    * (see e.g. Meni Rosenfeld's Proof-of-Activity paper http://eprint.iacr.org/2014/452.pdf)
    */
  def generators(block: Block): Seq[Account]

  def blockScore(block: Block): BigInt

  def blockOrdering(implicit transactionModule: TransactionModule): Ordering[(Block)] =
    Ordering.by {
      block =>
        val score = blockScore(block)
        val parent = transactionModule.blockStorage.history.blockById(block.referenceField.value).get
        val blockCreationTime = nextBlockGenerationTime(parent, block.signerDataField.value.generator)
          .getOrElse(block.timestampField.value)

        (score, -blockCreationTime)
    }

  def generateNextBlock(account: PrivateKeyAccount)
                           (implicit transactionModule: TransactionModule): Option[Block]

  def generateNextBlocks(accounts: Seq[PrivateKeyAccount])
                           (implicit transactionModule: TransactionModule): Seq[Block] =
    accounts.flatMap(generateNextBlock(_))

  def nextBlockGenerationTime(lastBlock: Block, account: PublicKeyAccount)
                             (implicit transactionModule: TransactionModule): Option[Long]

  def consensusBlockData(block: Block): NxtLikeConsensusBlockData
}

object ConsensusModule {

  /**
    * A naive but still a way to emphasize that cumulative score is sum of block scores
    */
  def cumulativeBlockScore(previousCumulativeScore: BigInt, blockScore: BigInt): BigInt = previousCumulativeScore + blockScore
}
