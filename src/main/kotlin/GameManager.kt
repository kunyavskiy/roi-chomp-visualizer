import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.PrintWriter
import kotlin.random.Random

class BadMoveException(message: String?) : Exception(message)

@Suppress("BlockingMethodInNonBlockingContext")
class GameManager(
        val fieldSize: Int,
        private val maxEatenByRandom: Int,
        private val secretLength: Int,
        private val drawMutex: Mutex,
        private val needDelays: Boolean
) {
    val ready = mutableStateOf(false)
    val inputFileName = "game.out"
    val outputFileName = "game.in"
    val columnHeights = Array(fieldSize) { mutableStateOf(fieldSize) }
    val gameLog = mutableStateOf<String?>(null)
    var secret: String? = null
    val gameError = mutableStateOf<String?>(null)
    private var input: BufferedReader? = null
    private var output: PrintWriter? = null
    private val rnd = Random(239)
    private val gameLogArray = mutableListOf<Pair<Int, Int>>()

    suspend fun runGame() {
        coroutineScope {
            launch {
                withContext(Dispatchers.IO) {
                    output = PrintWriter(createOutputPipe(outputFileName))
                }
            }
            launch {
                withContext(Dispatchers.IO) {
                    input = createInputPipe(inputFileName).bufferedReader()
                }
            }
        }
        ready.value = true
        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val output = output!!
                    val input = input!!
                    output.println("1 $fieldSize $maxEatenByRandom $secretLength")
                    secret = Array(secretLength) { rnd.nextInt(2) }.joinToString("")
                    output.println(secret!!)
                    output.flush()
                    var firstMove = true
                    var games = 0
                    while (runOneGame(input, output, firstMove)) {
                        games += 1
                        firstMove = !firstMove
                        runBlocking {
                            drawMutex.withLock {
                                for (i in 0 until fieldSize) {
                                    columnHeights[i].value = fieldSize
                                }
                            }
                        }
                    }
                    val builder = StringBuilder()
                    builder.append("2 $fieldSize $maxEatenByRandom $secretLength").append(System.lineSeparator())
                    builder.append(games).append(System.lineSeparator())
                    builder.append(gameLogArray.joinToString(System.lineSeparator()) { "${it.first} ${it.second}" })
                    gameLog.value = builder.toString()
                } catch (e: Exception) {
                    gameError.value = e.message ?: "Неизвестная ошибка"
                } finally {
                    input?.close()
                    output?.close()
                }
            }
        }
    }

    private suspend fun processMove(x: Int, y: Int) {
        drawMutex.withLock {
            if (columnHeights[x].value <= y) {
                throw BadMoveException("Невалидный ход: клетка (${x + 1}, ${y + 1}) уже закрашена")
            }
            for (i in x until fieldSize) {
                columnHeights[i].value = minOf(columnHeights[i].value, y)
            }
        }
        gameLogArray.add(Pair(x + 1, y + 1))
    }

    private fun countEaten(x: Int, y: Int) = columnHeights.asSequence().drop(x).sumBy { maxOf(0, it.value - y) }

    private suspend fun runOneGame(input: BufferedReader, output: PrintWriter, firstMove: Boolean): Boolean {
        var move = firstMove
        var firstPlayerMove = true
        while (columnHeights[0].value > 0) {
            if (move) {
                val line = input.readLine() ?: throw BadMoveException("Неожиданный конец вывода решения")
                if (firstPlayerMove && line.toIntOrNull() == 0) {
                    if (!firstMove) {
                        gameLogArray.removeLast()
                    }
                    return false
                }
                firstPlayerMove = false
                try {
                    val lineSplit = line.split(" ").takeIf { it.size == 2 } ?: throw NumberFormatException()
                    val (x, y) = lineSplit.map { it.toInt() }
                    if (x !in 1..fieldSize || y !in 1..fieldSize) {
                        throw BadMoveException("Ожидалось два числа от 1 до $fieldSize, а решение вывело $x $y")
                    }
                    processMove(x - 1, y - 1)
                } catch (_: NumberFormatException) {
                    throw BadMoveException("Ожидалось два числа, а решение вывело \"$line\"")
                }
            } else {
                val allMoves = (0 until fieldSize).asSequence().flatMap { x ->
                    (0 until columnHeights[x].value).asSequence().map { Pair(x, it) }
                }
                val randomMove = allMoves.filter {
                    it != Pair(0, 0) && countEaten(it.first, it.second) <= maxEatenByRandom
                }.toList().randomOrNull(rnd) ?: Pair(0, 0)
                processMove(randomMove.first, randomMove.second)
                output.println("${randomMove.first + 1} ${randomMove.second + 1}")
                output.flush()
                if (needDelays) {
                    delay(30)
                }
            }
            move = !move
        }
        return true
    }
}

