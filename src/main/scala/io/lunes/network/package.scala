package io.lunes

import java.net.{InetSocketAddress, SocketAddress, URI}
import java.util.concurrent.Callable

import cats.Eq
import io.lunes.state2.ByteStr
import io.netty.channel.group.{ChannelGroup, ChannelGroupFuture, ChannelMatcher}
import io.netty.channel.local.LocalAddress
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.util.NetUtil.toSocketAddressString
import io.netty.util.concurrent.{EventExecutorGroup, ScheduledFuture}
import monix.eval.Coeval
import monix.execution.Scheduler
import monix.reactive.Observable
import scorex.block.Block
import io.lunes.transaction.Transaction
import scorex.utils.ScorexLogging

import scala.concurrent.duration._

/**
  *
  */
package object network extends ScorexLogging {
  def inetSocketAddress(addr: String, defaultPort: Int): InetSocketAddress = {
    val uri = new URI(s"node://$addr")
    if (uri.getPort < 0) new InetSocketAddress(addr, defaultPort)
    else new InetSocketAddress(uri.getHost, uri.getPort)
  }

  /**
    *
    * @param e
    */
  implicit class EventExecutorGroupExt(val e: EventExecutorGroup) extends AnyVal {
    /**
      *
      * @param initialDelay
      * @param delay
      * @param f
      * @return
      */
    //TODO: Tailirec
    def scheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration)(f: => Unit): ScheduledFuture[_] =
      e.scheduleWithFixedDelay((() => f): Runnable, initialDelay.toNanos, delay.toNanos, NANOSECONDS)

    /**
      *
      * @param delay
      * @param f
      * @tparam A
      * @return
      */
    //TODO: Tailrec
    def schedule[A](delay: FiniteDuration)(f: => A): ScheduledFuture[A] =
      e.schedule((() => f): Callable[A], delay.length, delay.unit)
  }

  private def formatAddress(sa: SocketAddress) = sa match {
    case null => ""
    case l: LocalAddress => s" $l"
    case isa: InetSocketAddress => s" ${toSocketAddressString(isa)}"
    case x => s" $x" // For EmbeddedSocketAddress
  }

  /**
    *
    * @param ctx
    * @return
    */
  //TODO: TailRec
  def id(ctx: ChannelHandlerContext): String = id(ctx.channel())

  /**
    *
    * @param chan
    * @param prefix
    * @return
    */
  def id(chan: Channel, prefix: String = ""): String = s"[$prefix${chan.id().asShortText()}${formatAddress(chan.remoteAddress())}]"

  /**
    *
    * @param blocks
    * @return
    */
  def formatBlocks(blocks: Seq[Block]): String = formatSignatures(blocks.view.map(_.uniqueId))

  /**
    *
    * @param signatures
    * @return
    */
  def formatSignatures(signatures: Seq[ByteStr]): String = if (signatures.isEmpty) "[Empty]"
  else if (signatures.size == 1) s"[${signatures.head.trim}]"
  else s"(total=${signatures.size}) [${signatures.head.trim} -- ${signatures.last.trim}]"

  implicit val channelEq: Eq[Channel] = Eq.fromUniversalEquals

  /**
    *
    * @param ctx
    */
  implicit class ChannelHandlerContextExt(val ctx: ChannelHandlerContext) extends AnyVal {
    /**
      *
      * @return
      */
    def remoteAddress: Option[InetSocketAddress] = ctx.channel() match {
      case x: NioSocketChannel => Option(x.remoteAddress())
      case x =>
        log.debug(s"Doesn't know how to get a remoteAddress from ${id(ctx)}, $x")
        None
    }
  }

  /**
    *
    * @param allChannels
    */
  implicit class ChannelGroupExt(val allChannels: ChannelGroup) extends AnyVal {
    /**
      *
      * @param message
      * @param except
      */
    def broadcast(message: AnyRef, except: Option[Channel] = None): Unit = broadcast(message, except.toSet)

    /**
      *
      * @param message
      * @param except
      * @return
      */
    def broadcast(message: AnyRef, except: Set[Channel]): ChannelGroupFuture = {
      logBroadcast(message, except)
      allChannels.writeAndFlush(message, { (channel: Channel) => !except.contains(channel) })
    }

    /**
      *
      * @param messages
      * @param except
      */
    def broadcastMany(messages: Seq[AnyRef], except: Set[Channel] = Set.empty): Unit = {
      val channelMatcher: ChannelMatcher = { (channel: Channel) => !except.contains(channel) }
      messages.foreach { message =>
        logBroadcast(message, except)
        allChannels.write(message, channelMatcher)
      }

      allChannels.flush(channelMatcher)
    }

    /**
      *
      * @param tx
      * @param except
      */
    def broadcastTx(tx: Transaction, except: Option[Channel] = None): Unit =
      allChannels.broadcast(RawBytes(TransactionSpec.messageCode, tx.bytes()), except)

    /**
      *
      * @param txs
      */
    def broadcastTx(txs: Seq[Transaction]): Unit =
      allChannels.broadcastMany(txs.map(tx => RawBytes(TransactionSpec.messageCode, tx.bytes())))

    private def logBroadcast(message: AnyRef, except: Set[Channel]): Unit = message match {
      case RawBytes(TransactionSpec.messageCode, _) =>
      case _ =>
        val exceptMsg = if (except.isEmpty) "" else s" (except ${except.map(id(_)).mkString(", ")})"
        log.trace(s"Broadcasting $message to ${allChannels.size()} channels$exceptMsg")
    }
  }

  /**
    *
    * @tparam A Channel Observable Type.
    */
  type ChannelObservable[A] = Observable[(Channel, A)]

  /**
    *
    * @param o
    * @param s
    * @tparam A
    * @return
    */
  def lastObserved[A](o: Observable[A])(implicit s: Scheduler): Coeval[Option[A]] = {
    @volatile var last = Option.empty[A]
    o.foreach(a => last = Some(a))
    Coeval(last)
  }

  /**
    *
    * @param o
    * @param s
    * @tparam A
    * @return
    */
  def newItems[A](o: Observable[A])(implicit s: Scheduler): Coeval[Seq[A]] = {
    @volatile var collected = Seq.empty[A]
    o.foreach(a => collected = collected :+ a)
    Coeval {
      val r = collected
      collected = Seq.empty
      r
    }
  }
}
