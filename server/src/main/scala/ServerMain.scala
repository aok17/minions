package minionsgame.server

import scala.util.{Try,Success,Failure}
import scala.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.config.ConfigFactory
import java.util.Calendar
import java.text.SimpleDateFormat

import akka.actor.{ActorSystem, Actor, ActorRef, Terminated, Props, Status}
import akka.stream.{ActorMaterializer,OverflowStrategy}
import akka.stream.scaladsl.{Flow,Sink,Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message,TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.event.Logging

import minionsgame.core._

object Paths {
  val applicationConf = "./application.conf"
  val mainPage = "./web/index.html"
  val webjs = "./web/js/"
}

object ServerMain extends App {
  val config = ConfigFactory.parseFile(new java.io.File(Paths.applicationConf))

  implicit val actorSystem = ActorSystem("gameSystem",config)
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ")
  def log(s: String): Unit = {
    println(timeFormat.format(Calendar.getInstance().getTime()) + " " + s)
  }

  val cwd = new java.io.File(".").getCanonicalPath
  log("Running in " + cwd)

  val interface = config.getString("app.interface")
  val port = config.getInt("app.port")

  val numBoards = config.getInt("app.numBoards")

  val boards: Array[Board] = {
    val topology = HexTopology
    val boards = (0 until numBoards).toArray.map { _ =>
      val plane: Plane[Terrain] = Plane.create(12, 12, topology, Ground)
      plane(1,0) = Water

      val state = BoardState.create(plane)
      state.spawnPieceInitial(S0, Units.zombie.name, Loc(2,1))
      state.spawnPieceInitial(S0, Units.zombie.name, Loc(2,2))
      state.spawnPieceInitial(S0, Units.zombie.name, Loc(2,2))

      state.spawnPieceInitial(S0, Units.zombie.name, Loc(2,3))
      state.spawnPieceInitial(S0, Units.zombie.name, Loc(2,3))
      state.spawnPieceInitial(S0, Units.zombie.name, Loc(2,3))

      state.spawnPieceInitial(S1, Units.zombie.name, Loc(3,2))
      state.spawnPieceInitial(S1, Units.zombie.name, Loc(3,4))
      state.spawnPieceInitial(S1, Units.zombie.name, Loc(1,4))
      state.spawnPieceInitial(S1, Units.zombie.name, Loc(2,5))

      Board.create(state)
    }
    boards
  }
  val boardSequences: Array[Int] = (0 until numBoards).toArray.map { _ => 0}

  sealed trait GameActorEvent
  case class UserJoined(val sessionId: Int, val username: String, val side: Option[Side], val out: ActorRef) extends GameActorEvent
  case class UserLeft(val sessionId: Int) extends GameActorEvent
  case class QueryStr(val sessionId: Int, val queryStr: String) extends GameActorEvent

  private class GameActor extends Actor {
    //The actor refs are basically the writer end of a pipe where we can stick messages to go out
    //to the players logged into the server
    var sessionOfUsername: Map[String,Int] = Map()
    var usernameOfSession: Map[Int,String] = Map()
    var userSides: Map[Int,Option[Side]] = Map()
    var userOuts: Map[Int,ActorRef] = Map()

    // private def broadcast(response: Protocol.Response, side: Option[Side]): Unit = {
    //   userOuts.foreach { case (sid,out) =>
    //     if(userSides(sid) == side) out ! response
    //   }
    // }
    private def broadcastAll(response: Protocol.Response): Unit = {
      userOuts.foreach { case (_,out) =>
        out ! response
      }
    }

    private def handleQuery(query: Protocol.Query, out: ActorRef, side: Option[Side]): Unit = {
      query match {
        case Protocol.Heartbeat(i) =>
          out ! Protocol.OkHeartbeat(i)

        case Protocol.RequestGeneralState =>
          //TODO
          out ! Protocol.QueryError("Not implemented yet")

        case Protocol.RequestBoardHistory(boardIdx) =>
          if(boardIdx < 0 || boardIdx >= numBoards)
            out ! Protocol.QueryError("Invalid boardIdx")
          else {
            out ! Protocol.ReportBoardHistory(
              boardIdx,
              boards(boardIdx).toSummary(),
              boardSequences(boardIdx)
            )
          }

        case Protocol.DoBoardAction(boardIdx,boardAction) =>
          log("Received board " + boardIdx + " action " + boardAction)
          side match {
            case None =>
              out ! Protocol.QueryError("Cannot perform actions as a spectator")
            case Some(side) =>
              if(boardIdx < 0 || boardIdx >= numBoards)
                out ! Protocol.QueryError("Invalid boardIdx")
              else if(boards(boardIdx).curState().side != side)
                out ! Protocol.QueryError("Currently the other team's turn")
              else {
                boards(boardIdx).doAction(boardAction) match {
                  case Failure(e) =>
                    out ! Protocol.QueryError("Illegal action: " + e.toString)
                  case Success(()) =>
                    boardSequences(boardIdx) += 1
                    out ! Protocol.OkBoardAction(boardIdx,boardSequences(boardIdx))
                    broadcastAll(Protocol.ReportBoardAction(boardIdx,boardAction,boardSequences(boardIdx)))
                }
              }
          }
        case Protocol.UndoBoardAction(boardIdx,actionId,boardSequence) =>
          side match {
            case None =>
              out ! Protocol.QueryError("Cannot perform actions as a spectator")
            case Some(side) =>
              if(boardIdx < 0 || boardIdx >= numBoards)
                out ! Protocol.QueryError("Invalid boardIdx")
              else if(boardSequence != boardSequences(boardIdx))
                out ! Protocol.QueryError("Client board out-of-sync with server board")
              else if(boards(boardIdx).curState().side != side)
                out ! Protocol.QueryError("Currently the other team's turn")
              else {
                boards(boardIdx).undo() match {
                  case Failure(e) =>
                    out ! Protocol.QueryError("Illegal undo: " + e.toString)
                  case Success(()) =>
                    boardSequences(boardIdx) += 1
                    out ! Protocol.OkUndoBoardAction(boardIdx,actionId,boardSequences(boardIdx))
                    broadcastAll(Protocol.ReportUndoBoardAction(boardIdx,actionId,boardSequences(boardIdx)))
                }
              }
          }
        case Protocol.RedoBoardAction(boardIdx,actionId,boardSequence) =>
          side match {
            case None =>
              out ! Protocol.QueryError("Cannot perform actions as a spectator")
            case Some(side) =>
              if(boardIdx < 0 || boardIdx >= numBoards)
                out ! Protocol.QueryError("Invalid boardIdx")
              else if(boardSequence != boardSequences(boardIdx))
                out ! Protocol.QueryError("Client board out-of-sync with server board")
              else if(boards(boardIdx).curState().side != side)
                out ! Protocol.QueryError("Currently the other team's turn")
              else {
                //TODO will need to check general stuffs
                boards(boardIdx).redo() match {
                  case Failure(e) =>
                    out ! Protocol.QueryError("Illegal redo: " + e.toString)
                  case Success(()) =>
                    boardSequences(boardIdx) += 1
                    out ! Protocol.OkRedoBoardAction(boardIdx,actionId,boardSequences(boardIdx))
                    broadcastAll(Protocol.ReportRedoBoardAction(boardIdx,actionId,boardSequences(boardIdx)))
                }
              }
          }
      }
    }

    def terminateWebsocket(out: ActorRef): Unit = {
      //Websocket closes if you send it Status.Success
      out ! Status.Success("")
    }

    def handleUserLeft(sessionId: Int) = {
      if(usernameOfSession.contains(sessionId)) {
        val username = usernameOfSession(sessionId)
        val side = userSides(sessionId)
        broadcastAll(Protocol.UserLeft(username,side))
        val out = userOuts(sessionId)
        usernameOfSession = usernameOfSession - sessionId
        userSides = userSides - sessionId
        userOuts = userOuts - sessionId
        terminateWebsocket(out)
        log("UserLeft: " + username + " Side: " + side)
      }
    }

    override def receive: Receive = {
      case UserJoined(sessionId, username, side, out) =>
        if(sessionOfUsername.contains(username)) {
          val oldSessionId = sessionOfUsername(username)
          handleUserLeft(oldSessionId)
        }

        sessionOfUsername = sessionOfUsername + (username -> sessionId)
        usernameOfSession = usernameOfSession + (sessionId -> username)
        userSides = userSides + (sessionId -> side)
        userOuts = userOuts + (sessionId -> out)
        out ! Protocol.Version(CurrentVersion.version)
        out ! Protocol.InitializeBoards(boards.map { board => board.toSummary()},boardSequences.clone())
        broadcastAll(Protocol.UserJoined(username,side))
        log("UserJoined: " + username + " Side: " + side)

      case UserLeft(sessionId) =>
        if(usernameOfSession.contains(sessionId)) {
          val username = usernameOfSession(sessionId)
          val side = userSides(sessionId)
          broadcastAll(Protocol.UserLeft(username,side))
          val out = userOuts(sessionId)
          usernameOfSession = usernameOfSession - sessionId
          userSides = userSides - sessionId
          userOuts = userOuts - sessionId
          out ! Status.Success("")
          log("UserLeft: " + username + " Side: " + side)
        }
      case QueryStr(sessionId, queryStr) =>
        if(usernameOfSession.contains(sessionId)) {
          val out = userOuts(sessionId)
          import play.api.libs.json._
          Try(Json.parse(queryStr)) match {
            case Failure(err) => out ! Protocol.QueryError("Could not parse as json: " + err.toString)
            case Success(json) =>
              json.validate[Protocol.Query] match {
                case (e: JsError) => out ! Protocol.QueryError("Could not parse as query: " + JsError.toJson(e).toString())
                case (s: JsSuccess[Protocol.Query]) =>
                  val query = s.get
                  handleQuery(query, out, userSides(sessionId))
              }
          }
        }
    }

  }

  val gameActor = actorSystem.actorOf(Props(classOf[GameActor]))
  val nextSessionId = new AtomicInteger()

  def websocketMessageFlow(username: String, sideStr: Option[String]) : Flow[Message, Message, _] = {
    val side: Option[Side] = sideStr match {
      case Some("0") => Some(S0)
      case Some("1") => Some(S1)
      case Some(s) => throw new Exception("Invalid side: " + s)
      case None => None
    }

    val sessionId = nextSessionId.getAndIncrement()

    //Create output stream for the given user
    val responseBufferSize = 16 //Buffer messages to the user before failing

    //Specifies a sink where the values are made by a flow of Messages
    //and mapping them and then feeding them to the GameActor
    val in: Sink[Message,_] = {
      Flow[Message].collect { message: Message =>
        message match {
          case TextMessage.Strict(text) =>
            Future.successful(text)
          case TextMessage.Streamed(textStream) =>
            textStream.runFold("")(_ + _)
        }
      } .mapAsync(1)((str:Future[String]) => str)
        .map { (str: String) => QueryStr(sessionId,str): GameActorEvent }
        .to(Sink.actorRef[GameActorEvent](gameActor, onCompleteMessage = UserLeft(sessionId)))
    }

    //Specifies a source made by materializing an Actor, where the source's values are those that
    //are fed to the Actor, followed by a map that converts them to text messages
    val out: Source[Message,_] = {
      Source.actorRef[Protocol.Response](responseBufferSize, OverflowStrategy.fail)
        .mapMaterializedValue(actorRef => gameActor ! UserJoined(sessionId,username,side,actorRef))
        .map { response: Protocol.Response =>
          import play.api.libs.json._
          TextMessage(Json.stringify(Json.toJson(response))) : Message
        }
    }

    Flow.fromSinkAndSource(in, out)
  }

  val route = get {
    pathEndOrSingleSlash {
      parameter("username") { username =>
        //Ignore username, just make sure the user provided it
        val _ = username
        getFromFile(Paths.mainPage)
      } ~
      pass {
        complete("Please provide 'username=' in URL")
      }
    } ~
    pathPrefix("js") {
      getFromDirectory(Paths.webjs)
    }
  } ~
  path("playGame") {
    parameter("username") { username =>
      parameter("side") { side =>
        Try(websocketMessageFlow(username,Some(side))) match {
          case Failure(exn) => complete(exn.toString)
          case Success(flow) => handleWebSocketMessages(flow)
        }
      } ~
      pass {
        Try(websocketMessageFlow(username,None)) match {
          case Failure(exn) => complete(exn.toString)
          case Success(flow) => handleWebSocketMessages(flow)
        }
      }
    }
  }

  val binding = Http().bindAndHandle(route, interface, port)

  binding.onComplete {
    case Failure(e) =>
      log(s"Server http binding failed ${e.getMessage}")
      actorSystem.terminate()
    case Success(binding) =>
      val localAddress = binding.localAddress
      log(s"Server is listening on ${localAddress.getHostName}:${localAddress.getPort}")
      scala.io.StdIn.readLine()
      log("Done")
      actorSystem.terminate()
  }

}
