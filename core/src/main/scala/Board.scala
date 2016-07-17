import scala.util.{Try,Success,Failure}
import scala.collection.immutable.Vector

import RichImplicits._

/**
  * The second layer of the board implementation. See BoardState.scala for the more primitive layer below.
  * Actions and semantics on this layer are more at the level of the UI, rather than the direct legal actions in the game.
  * In particular, this layer implements undo/redo and action reordering that make the UI friendly:
  *
  * - Spawn PlayerActions are reordered to happen after all other PlayerActions. This allows us to legality-check everything
  *   AS IF all spawns happened at the end of the turn in a distinct "spawn phase" as required by the technical rules of minions,
  *   and yet permits us to have a UI that in practice allows users to interleave spawns and other actions.
  *
  * - Local undos allow a user to specify a piece and undo all actions involving that piece this turn. We do this simply by stripping
  *   out all actions involving the piece, and legality-checking the remaining sequence of actions, tossing out any that don't legality check.
  *
  * In general, we deal with any dependency conflicts from reordering of actions by throwing out actions that became illegal.
  *
  * As a slight detail, GeneralActions require communication with the outside world when doing them or attempting to undo or redo them.
  * Doing or redoing requires claiming a shared resource (like spending mana to buy a unit), undoing requires informing the broader game
  * the resource is available again (like regaining the mana after undoing the purchase).
  * We handle this by exposing functions here to allow users to determine what is about to get undone or redone on an undo or redo,
  * namely prevAction and nextAction.
  * It's up to the user of Board to do the necessary work here.
  *
  */


/** Action:
  * A single UI/user action that can be made, undone, and redone.
  * These are the objects that conceptually form a stack that global undo/redo operates on - yes, this means that local undos
  * are treated the same way as a group of PlayerActions from the UI perspective - and the local undo itself can be undone/redone.
  */
sealed trait Action
case class PlayerActions(actions: List[PlayerAction]) extends Action
case class DoGeneralAction(action: GeneralAction) extends Action
case class LocalUndo(pieceSpec: PieceSpec) extends Action

//Pairs board states together with the legal history of actions that generated those states, after reordering of actions.
//A new BoardHistory is created after each Action.
case class BoardHistory(
  //State of the board after applying moving/attacking actions, so far
  val moveAttackState: BoardState,
  //State of the board after applying spawning actions, so far
  val spawnState: BoardState,

  //Moving/attacking actions taken by the side to move this turn
  val moveAttackActionsThisTurn: Vector[PlayerAction],
  //Spawning actions taken by the side to move this turn
  val spawnActionsThisTurn: Vector[PlayerAction],
  //General actions taken by the side to move this turn
  val generalActionsThisTurn: Vector[GeneralAction]
)

object BoardHistory {
  def initial(state: BoardState) = BoardHistory(
    moveAttackState = state.copy(),
    spawnState = state.copy(),
    moveAttackActionsThisTurn = Vector(),
    spawnActionsThisTurn = Vector(),
    generalActionsThisTurn = Vector()
  )
}

object Board {
  def create(initialState: BoardState): Board = {
    //Sanity check
    if(initialState.turnNumber > 0)
      throw new Exception("Attempting to create board with initial state with turnNumber > 0: " + initialState.turnNumber)

    new Board(
      initialState = initialState,
      initialStateThisTurn = initialState.copy(),
      actionsThisTurn = Vector(),
      historiesThisTurn = Vector(BoardHistory.initial(initialState)),
      curIdx = 0,
      actionsPrevTurns = Vector(),
      playerGeneralActionsPrevTurns = Vector()
    )
  }
}

class Board private (
  //The board state at the start of everything
  private val initialState: BoardState,
  //The board history at the start of this turn
  private var initialStateThisTurn: BoardState,

  //actionsThisTurn is defined in a potentially larger range if undos have happened, as it stores the redo history.
  //historiesThisTurn is defined in the range [0,curIdx].
  private var actionsThisTurn: Vector[Action],
  private var historiesThisTurn: Vector[BoardHistory],

  //Current index of action (decrements on undo, increments on redo)
  private var curIdx: Int,

  //Accumulates actionsThisTurn at the end of each turn
  private var actionsPrevTurns: Vector[Vector[Action]],
  //Actions over the history of the board over prior turns, at the internal rearranging level, rather than the UI level.
  private var playerGeneralActionsPrevTurns: Vector[(Vector[PlayerAction],Vector[GeneralAction])]
) {

  //Users should NOT modify the BoardState returned by this function!
  def curState(): BoardState = {
    historiesThisTurn(curIdx).spawnState
  }

  def tryLegality(action: Action): Try[Unit] = {
    action match {
      case PlayerActions(actions) => curState().tryLegality(actions)
      case DoGeneralAction(_) => Success(())
      case LocalUndo(pieceSpec) =>
        if(curState().pieceExists(pieceSpec))
          Success(())
        else
          Failure(new Exception("Cannot local-undo for a piece that doesn't exist"))
    }
  }

  //Due to action reordering, might also report some actions illegal that aren't reported as illegal by tryLegality,
  //but this should be extremely rare.
  def doAction(action: Action): Try[Unit] = {
    tryLegality(action) match {
      case Failure(err) => Failure(err)
      case Success(()) => Try {
        val history = historiesThisTurn(curIdx)
        val newHistory = action match {
          case PlayerActions(playerActions) =>
            val newMoveAttackState = history.moveAttackState.copy()

            //Try all the move/attack actions that are legal now in a row. Delay other actions to the spawn phase.
            var moveAttackActionsRev: List[PlayerAction] = List()
            var delayedToSpawnRev: List[PlayerAction] = List()
            playerActions.foreach { playerAction =>
              playerAction match {
                case Movements(_) | Attack(_,_,_) =>
                  //If move/attacks fail, then they're flat-out illegal
                  newMoveAttackState.doAction(playerAction).get
                  moveAttackActionsRev = playerAction :: moveAttackActionsRev
                case Spawn(_,_,_) =>
                  delayedToSpawnRev = playerAction :: delayedToSpawnRev
                case SpellsAndAbilities(_) =>
                  //When spells fail, it may be because they are targeting units only placed during spawn
                  newMoveAttackState.doAction(playerAction) match {
                    case Success(()) => moveAttackActionsRev = playerAction :: moveAttackActionsRev
                    case Failure(_) => delayedToSpawnRev = playerAction :: delayedToSpawnRev
                  }
              }
            }

            //Reapply all the spawn actions so far
            val newSpawnState = newMoveAttackState.copy()
            history.spawnActionsThisTurn.foreach { playerAction =>
              newSpawnState.doAction(playerAction).get
            }

            //And now apply all the deferred actions
            val spawnActions = delayedToSpawnRev.reverse
            spawnActions.foreach { playerAction =>
              newSpawnState.doAction(playerAction).get
            }

            BoardHistory(
              moveAttackState = newMoveAttackState,
              spawnState = newSpawnState,
              moveAttackActionsThisTurn = history.moveAttackActionsThisTurn ++ moveAttackActionsRev.reverse,
              spawnActionsThisTurn = history.spawnActionsThisTurn ++ spawnActions,
              generalActionsThisTurn = history.generalActionsThisTurn
            )
          case DoGeneralAction(generalAction) =>
            val newMoveAttackState = history.moveAttackState.copy()
            val newSpawnState = history.spawnState.copy()
            newMoveAttackState.doGeneralAction(generalAction)
            newSpawnState.doGeneralAction(generalAction)
            BoardHistory(
              moveAttackState = newMoveAttackState,
              spawnState = newSpawnState,
              moveAttackActionsThisTurn = history.moveAttackActionsThisTurn,
              spawnActionsThisTurn = history.spawnActionsThisTurn,
              generalActionsThisTurn = history.generalActionsThisTurn :+ generalAction
            )
          case LocalUndo(pieceSpec) =>
            val newMoveAttackState = initialStateThisTurn.copy()
            //Reapply all general actions
            //Note that this relies on the invariant mentioned at the top of BoardState.scala - that reordering general actions
            //to come before player actions never changes the legality of the total move sequence or the final result of that sequence.
            history.generalActionsThisTurn.foreach { generalAction =>
              newMoveAttackState.doGeneralAction(generalAction)
            }
            //Attempts to reapply a player action. Returns true if reapplied, false if not (illegal or involves the undo)
            def maybeApplyAction(state: BoardState, playerAction: PlayerAction) = {
              if(PlayerAction.involvesPiece(playerAction,pieceSpec))
                false
              else {
                state.doAction(playerAction) match {
                  case Success(()) => true
                  case Failure(_) => false
                }
              }
            }
            val newMoveAttackActionsThisTurn = history.moveAttackActionsThisTurn.filter { playerAction =>
              maybeApplyAction(newMoveAttackState,playerAction)
            }
            val newSpawnState = newMoveAttackState.copy()
            val newSpawnActionsThisTurn = history.spawnActionsThisTurn.filter { playerAction =>
              maybeApplyAction(newSpawnState,playerAction)
            }
            BoardHistory(
              moveAttackState = newMoveAttackState,
              spawnState = newSpawnState,
              moveAttackActionsThisTurn = newMoveAttackActionsThisTurn,
              spawnActionsThisTurn = newSpawnActionsThisTurn,
              generalActionsThisTurn = history.generalActionsThisTurn
            )
        }

        truncateRedos()
        actionsThisTurn = actionsThisTurn :+ action
        historiesThisTurn = historiesThisTurn :+ newHistory
        curIdx = curIdx + 1
        ()
      }
    }
  }

  //End the current turn and begin the next turn.
  def endTurn(): Unit = {
    val history = historiesThisTurn(curIdx)

    initialStateThisTurn = history.spawnState.copy()
    initialStateThisTurn.endTurn()
    actionsPrevTurns = actionsPrevTurns :+ actionsThisTurn
    actionsThisTurn = Vector()
    historiesThisTurn = Vector(BoardHistory.initial(initialStateThisTurn))

    val playerGeneralActions = (
      history.moveAttackActionsThisTurn ++ history.spawnActionsThisTurn,
      history.generalActionsThisTurn
    )
    playerGeneralActionsPrevTurns = playerGeneralActionsPrevTurns :+ playerGeneralActions
    curIdx = 0
  }

  //Undo the most recent Action
  def undo(): Try[Unit] = {
    if(curIdx <= 0)
      Failure(new Exception("Cannot undo, already at the start"))
    else {
      curIdx = curIdx - 1
      Success(())
    }
  }

  //Redo the most recent undone Action
  def redo(): Try[Unit] = {
    if(curIdx >= actionsThisTurn.length)
      Failure(new Exception("Cannot redo, already at the end"))
    else {
      curIdx = curIdx + 1
      Success(())
    }
  }

  //Return the action that would be undone by a call to undo
  def prevAction: Option[Action] = {
    if(curIdx <= 0)
      None
    else
      Some(actionsThisTurn(curIdx-1))
  }

  //Return the action that would be redone by a call to redo
  def nextAction: Option[Action] = {
    if(curIdx >= actionsThisTurn.length)
      None
    else
      Some(actionsThisTurn(curIdx))
  }

  private def truncateRedos(): Unit = {
    if(actionsThisTurn.length > curIdx)
      actionsThisTurn = actionsThisTurn.slice(0,curIdx)
  }
}