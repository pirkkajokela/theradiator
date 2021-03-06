package controllers

import play.api._
import play.api.mvc._
import scala.concurrent._
import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current

class NewRelic extends Controller {

  val config = Play.application.configuration
  val APIKEY = config.getString("newrelic.apikey").getOrElse("No key.")

  def index = Action.async {
  	loadData().map { case (apps, servers) => 
      Ok(views.html.nr.index(apps, servers))
  	}
  }

  def fragment = Action.async {
  	loadData().map { case (apps, servers) => 
      Ok(views.html.nr.fragment(apps, servers))
  	}
  }


  def loadData() : Future[(List[Block], List[Block])] = {
  	val resultFuture = WS.url("https://api.newrelic.com/v2/applications.json").withHeaders("X-Api-Key" -> APIKEY).get()
  	val jsonFuture = resultFuture.map { result => 
      result.json
  	}

  	val serverResultFuture = 
  		WS.url("https://api.newrelic.com/v2/servers.json").withHeaders("X-Api-Key" -> APIKEY)
  			.get()
  			.map { result => result.json }

  	val blockFuture = for(json <- jsonFuture;
  		                  serverJson <- serverResultFuture) yield {
      
  	  val serversJson = (serverJson \ "servers").as[List[JsObject]]

      val id2serverBlock = serversJson.map { server =>
        val id = (server \ "id").as[Long]
        val name = (server \ "name").as[String]
        val status = (server \ "health_status").asOpt[String]
        id -> Block(name, status.getOrElse("gray"), Nil)
      }.toMap

      def serverBlock(id: Long) = {
      	id2serverBlock.get(id).getOrElse(Block(id.toString, "unknown", Nil))
      }

      val appsJson = (json \ "applications").as[List[JsObject]]
      val appsBlocks = appsJson.map { appJson =>
        val name = (appJson \ "name").as[String]
        val status = (appJson \ "health_status").as[String]
        val instances = (appJson \ "links" \ "servers").as[List[Long]]
        Block(name, status, instances.map(i=>serverBlock(i)))
      }.sortWith(_.header < _.header)

      val usedServers = appsBlocks.flatMap(a=>a.parts)
      val nonAppsServers = id2serverBlock.values.toSet -- usedServers

      (appsBlocks, nonAppsServers.toList)
  	}
  	blockFuture
  }

}

case class Block(header: String, status: String, parts: List[Block]) {
  val cssClasses = status + (if(header.length > 20) " longtext" else "")
}

