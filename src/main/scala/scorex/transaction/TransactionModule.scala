package scorex.transaction

import scorex.block.{Block, BlockField, BlockProcessingModule}

import scala.util.Try

trait TransactionModule {

  def parseBytes(bytes: Array[Byte]): Try[BlockField[Seq[Transaction]]]

  def genesisData: BlockField[Seq[Transaction]]

  val blockStorage: BlockStorage

  val utxStorage: UnconfirmedTransactionsStorage

  def isValid(block: Block): Boolean

  def isValid(tx: Transaction, blockTime: Long): Boolean

  def transactions(block: Block): Seq[Transaction]

  def unconfirmedTxs: Seq[Transaction]

  def putUnconfirmedIfNew(tx: Transaction): Boolean

  def packUnconfirmed(heightOpt: Option[Int] = None): Seq[Transaction]

  def clearFromUnconfirmed(data: Seq[Transaction]): Unit

  def onNewOffchainTransaction(transaction: Transaction): Unit

  lazy val balancesSupport: Boolean = blockStorage.state match {
    case _: State with BalanceSheet => true
    case _ => false
  }

  lazy val accountWatchingSupport: Boolean = blockStorage.state match {
    case _: State with AccountTransactionsHistory => true
    case _ => false
  }
}
