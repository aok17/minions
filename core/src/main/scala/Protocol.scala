package minionsgame.core
import scala.reflect.ClassTag
import play.api.libs.json._

object Protocol {
  sealed trait Message
  case class Version(version: String) extends Message
  case class NumBoards(numBoards: Int) extends Message
  case class ReportBoardAction(boardIdx: Int, boardAction: BoardAction) extends Message
  case class ReportBoardState(boardIdx: Int, boardState: BoardState) extends Message

  sealed trait Query
  case object RequestGeneralState extends Query
  case class RequestBoardState(boardIdx: Int) extends Query
  case class DoBoardAction(boardIdx: Int, boardBction: BoardAction) extends Query


  //Conversions----------------------------------------------------
  def readsFromString[T](typeName: String)(f: String => T): Reads[T] = {
    new Reads[T]{
      def reads(json: JsValue): JsResult[T] = json match {
        case JsString(s) => JsSuccess(f(s))
        case _ => JsError("JSON string value expected when parsing " + typeName)
      }
    }
  }
  def writesToString[T](f: T => String): Writes[T] = {
    new Writes[T]{
      def writes(t: T): JsValue = JsString(f(t))
    }
  }

  def readsFromPair[T](typeName: String, convMap: Map[String, (JsValue => JsResult[T])]): Reads[T] = {
    new Reads[T]{
      def fail(): JsResult[T] =
        JsError("JSON (string*value) pair expected when parsing " + typeName)
      def reads(json: JsValue): JsResult[T] = json match {
        case JsArray(arr) =>
          if(arr.length != 2) fail()
          else {
            arr(0) match {
              case JsString(s) =>
                convMap.get(s) match {
                  case None => fail()
                  case Some(f) => f(arr(1))
                }
              case _ => fail()
            }
          }
        case _ => fail()
      }
    }
  }
  def jsPair(variantName: String, jsValue: JsValue): JsValue = {
    JsArray(Array(JsString(variantName),jsValue))
  }

  //CommonTypes.scala--------------------------------------------------------------------------------
  implicit val locFormat = Json.format[Loc]
  implicit val vecFormat = Json.format[Vec]

  implicit val sideFormat = {
    val reads: Reads[Side] = readsFromString[Side]("Side")(Side.ofString)
    val writes: Writes[Side] = writesToString[Side](side => side.toString)
    Format(reads,writes)
  }

  implicit val spawnerFormat = Json.format[Spawner]
  implicit val terrainFormat = {
    val reads: Reads[Terrain] = readsFromPair[Terrain]("Terrain",Map(
      "Wall" -> ((_:JsValue) => JsSuccess(Wall: Terrain)),
      "Ground" -> ((_:JsValue) => JsSuccess(Ground: Terrain)),
      "Water" -> ((_:JsValue) => JsSuccess(Water: Terrain)),
      "Graveyard" -> ((_:JsValue) => JsSuccess(Graveyard: Terrain)),
      "Spawner" -> ((json:JsValue) => spawnerFormat.reads(json))
    ))
    val writes: Writes[Terrain] = new Writes[Terrain] {
      def writes(t: Terrain): JsValue = t match {
        case (Wall) => jsPair("Wall",JsString(""))
        case (Ground) => jsPair("Ground",JsString(""))
        case (Water) => jsPair("Water",JsString(""))
        case (Graveyard) => jsPair("Graveyard",JsString(""))
        case (t:Spawner) => jsPair("Spawner",spawnerFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

  def mapJsResults[T,U:ClassTag](arr:IndexedSeq[T])(f: T => JsResult[U]): JsResult[Array[U]] = {
    val len = arr.length
    def loop(acc: List[U], idx: Int): JsResult[Array[U]] = {
      if(idx >= len)
        JsSuccess(acc.toArray.reverse)
      else {
        f(arr(idx)) match {
          case (e: JsError) => e
          case (s: JsSuccess[U]) => loop(s.get :: acc, idx+1)
        }
      }
    }
    loop(Nil,0)
  }

  implicit def sideArrayFormat[T:ClassTag](implicit format: Format[T]) : Format[SideArray[T]] = {
    new Format[SideArray[T]] {
      def fail(): JsResult[SideArray[T]] =
        JsError("JSON pair expected when parsing side array")
      def reads(json: JsValue): JsResult[SideArray[T]] = json match {
        case JsArray(arr) =>
          if(arr.length != 2) fail()
          else mapJsResults(arr)(format.reads).map{ (arr:Array[T]) => SideArray.ofArrayInplace(arr) }
        case _ => fail()
      }
      def writes(sa: SideArray[T]): JsValue = {
        val arr = sa.toArrayInplace
        JsArray(Array(format.writes(arr(0)),format.writes(arr(1))))
      }
    }
  }

  implicit def mapFormat[T:ClassTag,U:ClassTag](implicit formatKey: Format[T], formatValue: Format[U]): Format[Map[T,U]] = {
    new Format[Map[T,U]] {
      def reads(json: JsValue): JsResult[Map[T,U]] = {
        Json.fromJson[List[(T,U)]](json).map { list => list.toMap }
      }
      def writes(map: Map[T,U]): JsValue = {
        Json.toJson(map.toList)
      }
    }
  }

  implicit val planeTopologyFormat = {
    val reads: Reads[PlaneTopology] = readsFromString[PlaneTopology]("PlaneTopology")(PlaneTopology.ofString)
    val writes: Writes[PlaneTopology] = writesToString[PlaneTopology](planeTopology => planeTopology.toString)
    Format(reads,writes)
  }

  implicit def planeFormat[T:ClassTag](implicit format: Format[T]) : Format[Plane[T]] = {
    import play.api.libs.json.Reads._
    import play.api.libs.functional.syntax._
    def getValue(obj:scala.collection.Map[String,JsValue], str: String): JsResult[JsValue] = {
      obj.get(str) match {
        case None => JsError("When parsing plane failed to find field: " + str)
        case Some(x) => JsSuccess(x)
      }
    }
    def getArray(obj:scala.collection.Map[String,JsValue], str: String): JsResult[IndexedSeq[JsValue]] = {
      obj.get(str) match {
        case None => JsError("When parsing plane failed to find field: " + str)
        case Some(JsArray(x)) => JsSuccess(x)
        case Some(_) => JsError("When parsing plane field " + str + " was not an array")
      }
    }
    def getInt(obj:scala.collection.Map[String,JsValue], str: String): JsResult[Int] = {
      obj.get(str) match {
        case None => JsError("When parsing plane failed to find field: " + str)
        case Some(JsNumber(x)) => JsSuccess(x.intValue)
        case Some(_) => JsError("When parsing plane field " + str + " was not an integer")
      }
    }
    new Format[Plane[T]] {
      def reads(json: JsValue): JsResult[Plane[T]] = json match {
        case JsObject(obj) =>
          getInt(obj,"xSize").flatMap { xSize =>
            getInt(obj,"ySize").flatMap { ySize =>
              getValue(obj,"topology").flatMap { topology =>
                planeTopologyFormat.reads(topology).flatMap { topology =>
                  getArray(obj,"arr").flatMap { arr =>
                    mapJsResults(arr)(format.reads).map { (arr:Array[T]) =>
                      new Plane(xSize,ySize,topology,arr)
                    }
                  }
                }
              }
            }
          }
        case _ => JsError("JSON object expected when parsing plane")
      }
      def writes(plane: Plane[T]): JsValue = {
        JsObject(Map(
          "xSize" -> JsNumber(plane.xSize),
          "ySize" -> JsNumber(plane.ySize),
          "topology" -> planeTopologyFormat.writes(plane.topology),
          "arr" -> JsArray(plane.getArrayInplace.map(format.writes))
        ))
      }
    }
  }

  //BoardState.scala--------------------------------------------------------------------------------
  implicit val startedTurnWithIDFormat = Json.format[StartedTurnWithID]
  implicit val spawnedThisTurnFormat = Json.format[SpawnedThisTurn]
  implicit val pieceSpecFormat = {
    val reads: Reads[PieceSpec] = readsFromPair[PieceSpec]("PieceSpec",Map(
      "StartedTurnWithID" -> ((json:JsValue) => startedTurnWithIDFormat.reads(json)),
      "SpawnedThisTurn" -> ((json:JsValue) => spawnedThisTurnFormat.reads(json))
    ))
    val writes: Writes[PieceSpec] = new Writes[PieceSpec] {
      def writes(t: PieceSpec): JsValue = t match {
        case (t:StartedTurnWithID) => jsPair("StartedTurnWithID",startedTurnWithIDFormat.writes(t))
        case (t:SpawnedThisTurn) => jsPair("SpawnedThisTurn",spawnedThisTurnFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

  implicit val movementFormat = Json.format[Movement]
  implicit val movementsFormat = Json.format[Movements]
  implicit val attackFormat = Json.format[Attack]
  implicit val spawnFormat = Json.format[Spawn]
  implicit val playedSpellOrAbilityFormat = Json.format[PlayedSpellOrAbility]
  implicit val spellsAndAbilitiesFormat = Json.format[SpellsAndAbilities]
  implicit val playerActionFormat = {
    val reads: Reads[PlayerAction] = readsFromPair[PlayerAction]("PlayerAction",Map(
      "Movements" -> ((json:JsValue) => movementsFormat.reads(json)),
      "Attack" -> ((json:JsValue) => attackFormat.reads(json)),
      "Spawn" -> ((json:JsValue) => spawnFormat.reads(json)),
      "SpellsAndAbilities" -> ((json:JsValue) => spellsAndAbilitiesFormat.reads(json))
    ))
    val writes: Writes[PlayerAction] = new Writes[PlayerAction] {
      def writes(t: PlayerAction): JsValue = t match {
        case (t:Movements) => jsPair("Movements",movementsFormat.writes(t))
        case (t:Attack) => jsPair("Attack",attackFormat.writes(t))
        case (t:Spawn) => jsPair("Spawn",spawnFormat.writes(t))
        case (t:SpellsAndAbilities) => jsPair("SpellsAndAbilities",spellsAndAbilitiesFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

  implicit val buyReinforcementFormat = Json.format[BuyReinforcement]
  implicit val gainSpellFormat = Json.format[GainSpell]
  implicit val revealSpellFormat = Json.format[RevealSpell]
  implicit val generalBoardActionFormat = {
    val reads: Reads[GeneralBoardAction] = readsFromPair[GeneralBoardAction]("GeneralBoardAction",Map(
      "BuyReinforcement" -> ((json:JsValue) => buyReinforcementFormat.reads(json)),
      "GainSpell" -> ((json:JsValue) => gainSpellFormat.reads(json)),
      "RevealSpell" -> ((json:JsValue) => revealSpellFormat.reads(json))
    ))
    val writes: Writes[GeneralBoardAction] = new Writes[GeneralBoardAction] {
      def writes(t: GeneralBoardAction): JsValue = t match {
        case (t:BuyReinforcement) => jsPair("BuyReinforcement",buyReinforcementFormat.writes(t))
        case (t:GainSpell) => jsPair("GainSpell",gainSpellFormat.writes(t))
        case (t:RevealSpell) => jsPair("RevealSpell",revealSpellFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

  implicit val pieceModFormat = {
    val reads: Reads[PieceMod] = readsFromString[PieceMod]("PieceMod")(PieceMod.ofString)
    val writes: Writes[PieceMod] = writesToString[PieceMod](pieceMod => pieceMod.toString)
    Format(reads,writes)
  }

  implicit val pieceStatsFormat = {
    new Format[PieceStats] {
      def reads(json: JsValue): JsResult[PieceStats] = json match {
        case JsString(s) =>
          Units.pieceMap.get(s) match {
            case None => JsError("Unknown piece name: " + s)
            case Some(stats) => JsSuccess(stats)
          }
        case _ =>
          JsError("JSON string expected when parsing piece stats")
      }
      def writes(t: PieceStats): JsValue = {
        assert(t.isBaseStats)
        JsString(t.name)
      }
    }
  }

  implicit val movingFormat = Json.format[Moving]
  implicit val attackingFormat = Json.format[Attacking]
  implicit val actStateFormat = {
    val reads: Reads[ActState] = readsFromPair[ActState]("ActState",Map(
      "Moving" -> ((json:JsValue) => movingFormat.reads(json)),
      "Attacking" -> ((json:JsValue) => attackingFormat.reads(json)),
      "Spawning" -> ((_:JsValue) => JsSuccess(Spawning: ActState)),
      "DoneActing" -> ((_:JsValue) => JsSuccess(DoneActing: ActState))
    ))
    val writes: Writes[ActState] = new Writes[ActState] {
      def writes(t: ActState): JsValue = t match {
        case (t:Moving) => jsPair("Moving",movingFormat.writes(t))
        case (t:Attacking) => jsPair("Attacking",attackingFormat.writes(t))
        case (Spawning) => jsPair("Spawning",JsString(""))
        case (DoneActing) => jsPair("DoneActing",JsString(""))
      }
    }
    Format(reads,writes)
  }

  implicit val pieceModWithDurationFormat = Json.format[PieceModWithDuration]
  implicit val pieceFormat = Json.format[Piece]
  implicit val tileFormat = Json.format[Tile]
  implicit val boardStateFormat = Json.format[BoardState]


  //Board.scala--------------------------------------------------------------------------------
  implicit val playerActionsFormat = Json.format[PlayerActions]
  implicit val doGeneralBoardActionFormat = Json.format[DoGeneralBoardAction]
  implicit val localUndoFormat = Json.format[LocalUndo]
  implicit val boardActionFormat = {
    val reads: Reads[BoardAction] = readsFromPair[BoardAction]("BoardAction",Map(
      "PlayerActions" -> ((json:JsValue) => playerActionsFormat.reads(json)),
      "DoGeneralBoardAction" -> ((json:JsValue) => doGeneralBoardActionFormat.reads(json)),
      "LocalUndo" -> ((json:JsValue) => localUndoFormat.reads(json))
    ))
    val writes: Writes[BoardAction] = new Writes[BoardAction] {
      def writes(t: BoardAction): JsValue = t match {
        case (t:PlayerActions) => jsPair("PlayerActions",playerActionsFormat.writes(t))
        case (t:DoGeneralBoardAction) => jsPair("DoGeneralBoardAction",doGeneralBoardActionFormat.writes(t))
        case (t:LocalUndo) => jsPair("LocalUndo",localUndoFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

  //Protocol.scala--------------------------------------------------------------------------------
  implicit val versionFormat = Json.format[Version]
  implicit val numBoardsFormat = Json.format[NumBoards]
  implicit val reportBoardActionFormat = Json.format[ReportBoardAction]
  implicit val reportBoardStateFormat = Json.format[ReportBoardState]
  implicit val messageFormat = {
    val reads: Reads[Message] = readsFromPair[Message]("Message",Map(
      "Version" -> ((json:JsValue) => versionFormat.reads(json)),
      "NumBoards" -> ((json:JsValue) => numBoardsFormat.reads(json)),
      "ReportBoardAction" -> ((json:JsValue) => reportBoardActionFormat.reads(json)),
      "ReportBoardState" -> ((json:JsValue) => reportBoardStateFormat.reads(json)),
    ))
    val writes: Writes[Message] = new Writes[Message] {
      def writes(t: Message): JsValue = t match {
        case (t:Version) => jsPair("Version",versionFormat.writes(t))
        case (t:NumBoards) => jsPair("NumBoards",numBoardsFormat.writes(t))
        case (t:ReportBoardAction) => jsPair("ReportBoardAction",reportBoardActionFormat.writes(t))
        case (t:ReportBoardState) => jsPair("ReportBoardState",reportBoardStateFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

  implicit val requestBoardStateFormat = Json.format[RequestBoardState]
  implicit val doBoardActionFormat = Json.format[DoBoardAction]
  implicit val queryFormat = {
    val reads: Reads[Query] = readsFromPair[Query]("Query",Map(
      "RequestGeneralState" -> ((_:JsValue) => JsSuccess(RequestGeneralState: Query)),
      "RequestBoardState" -> ((json:JsValue) => requestBoardStateFormat.reads(json)),
      "DoBoardAction" -> ((json:JsValue) => doBoardActionFormat.reads(json)),
    ))
    val writes: Writes[Query] = new Writes[Query] {
      def writes(t: Query): JsValue = t match {
        case (RequestGeneralState) => jsPair("RequestGeneralState",JsString(""))
        case (t:RequestBoardState) => jsPair("RequestBoardState",requestBoardStateFormat.writes(t))
        case (t:DoBoardAction) => jsPair("DoBoardAction",doBoardActionFormat.writes(t))
      }
    }
    Format(reads,writes)
  }

}