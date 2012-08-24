package skirmish

import org.{ pircbotx => pb }
import java.net.URLEncoder.encode

object Bot {

  type Bot = pb.PircBotX
  val Greet = "hi wolsni.*".r
  val Proj = "proj (.*)".r
  val EvanResponse = "evanresponse.*".r
  val NewEvanResponse = "tell evan (.*)".r

  var evanResponses = List("You're not wrong, Walter.")

  val logic = new pb.hooks.ListenerAdapter[Bot] with pb.hooks.Listener[Bot] {
    override def onMessage(event: pb.hooks.events.MessageEvent[Bot]) {
      event.getMessage match {
        case Greet() => event.respond("Hey there!")
        case Proj(query) => event.respond("http://prj2epsg.org/search?terms=" + encode(query, "UTF-8"))
        case EvanResponse() => 
          val response = evanResponses(util.Random.nextInt(evanResponses.size))
          event.getBot().sendMessage(event.getChannel, response)
        case x => // ignore.
      }
    }

    override def onPrivateMessage(event: pb.hooks.events.PrivateMessageEvent[Bot]) {
      event.getMessage match {
        case NewEvanResponse(response) =>
          evanResponses ::= response
          event.respond("Yeah, sure. I'll tell him.")
        case _ => event.respond("I don't get it.")
      }
    }
  }

  def main(args: Array[String]) {
    val bot = new pb.PircBotX
    bot.getListenerManager().addListener(logic)
    bot.setName("wolsni")
    bot.connect("irc.freenode.net")
    bot.joinChannel("#opengeo-fp")
  }
}
