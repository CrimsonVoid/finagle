package com.twitter.finagle.http2.exp.transport

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.http.Request
import com.twitter.finagle.netty4.http.Bijections
import com.twitter.finagle.{Stack, Status}
import com.twitter.util.{Await, Awaitable}
import io.netty.buffer.Unpooled
import io.netty.channel.{Channel, ChannelHandler, ChannelInitializer}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http2.{
  Http2MultiplexCodec,
  Http2MultiplexCodecBuilder,
  Http2StreamFrameToHttpObjectCodec
}
import org.scalatest.FunSuite

class ClientSessionImplTest extends FunSuite {

  private[this] def await[T](t: Awaitable[T]): T =
    Await.result(t, 15.seconds)

  abstract class Ctx {
    def inboundInitializer: ChannelHandler = new ChannelInitializer[Channel] {
      def initChannel(ch: Channel): Unit =
        throw new IllegalStateException("Shouldn't get here.")
    }

    def params: Stack.Params = Stack.Params.empty

    def initializer: ChannelInitializer[Channel] =
      new ChannelInitializer[Channel] {
        def initChannel(ch: Channel): Unit = {
          ch.pipeline
            .addLast(new Http2StreamFrameToHttpObjectCodec(false, false))
        }
      }

    lazy val multiplexCodec: Http2MultiplexCodec =
      Http2MultiplexCodecBuilder
        .forClient(inboundInitializer)
        .build()

    lazy val testChannel: EmbeddedChannel = {
      val ch = new EmbeddedChannel(multiplexCodec)
      ch
    }
  }

  test("presents status as closed if the parent channel is closed") {
    new Ctx {
      val clientSession = new ClientSessionImpl(params, initializer, testChannel)
      assert(clientSession.status == Status.Open)

      testChannel.close()

      assert(clientSession.status == Status.Closed)
    }
  }

  test("Child streams present status as closed if the parent channel is closed") {
    new Ctx {
      val clientSession = new ClientSessionImpl(params, initializer, testChannel)
      val stream = await(clientSession.newChildTransport())
      assert(stream.status == Status.Open)

      testChannel.close()

      assert(stream.status == Status.Closed)
    }
  }

  test("No streams are initialized until the first write happens") {
    new Ctx {
      val clientSession = new ClientSessionImpl(params, initializer, testChannel)
      val stream = await(clientSession.newChildTransport())
      assert(stream.status == Status.Open)

      assert(multiplexCodec.connection().local().lastStreamCreated() == 0)

      val req = Bijections.finagle.requestToNetty(Request())
      await(stream.write(req))

      assert(multiplexCodec.connection().local().lastStreamCreated() > 0)
    }
  }

  test("Session that has received a GOAWAY reports its status as Closed") {
    new Ctx {
      val clientSession = new ClientSessionImpl(params, initializer, testChannel)
      assert(clientSession.status == Status.Open)
      multiplexCodec.connection().goAwayReceived(0, 0, Unpooled.EMPTY_BUFFER)
      assert(clientSession.status == Status.Closed)

    }
  }
}
