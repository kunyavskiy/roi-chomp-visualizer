import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import java.io.*
import kotlin.random.*

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
    var gamesPlayed = 0
    var gamesWon = 0
    private var input: BufferedReader? = null
    private var output: PrintWriter? = null
    private val rnd = Random(239)
    private val gameLogArray = mutableListOf<Pair<Int, Int>>()

    suspend fun runGame() {
        withContext(Dispatchers.IO) {
            launch {
                output = PrintWriter(createOutputPipe(outputFileName))
            }
            launch {
                input = createInputPipe(inputFileName).bufferedReader()
            }
        }
        ready.value = true
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

    private fun eatenMoreThan(x: Int, y: Int, limit: Int): Boolean {
        var sum = 0
        for (i in x until fieldSize) {
            if (columnHeights[i].value <= y) return false
            sum += columnHeights[i].value - y
            if (sum > limit) return true
        }
        return false
    }

    @OptIn(ExperimentalStdlibApi::class)
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
                val randomMove = sequence {
                    for (x in 0 until fieldSize) {
                        for (y in columnHeights[x].value - 1 downTo 0) {
                            if ((x == 0 && y == 0) || eatenMoreThan(x, y, maxEatenByRandom)) break
                            yield(Pair(x, y))
                        }
                    }
                }.foldIndexed(Pair(0, 0), { i, acc, value -> if (rnd.nextInt(i + 1) == 0) value else acc})
                processMove(randomMove.first, randomMove.second)
                output.println("${randomMove.first + 1} ${randomMove.second + 1}")
                output.flush()
                if (needDelays) {
                    delay(30)
                }
            }
            move = !move
        }
        gamesPlayed += 1
        if (move) {
            gamesWon += 1
        }
        return true
    }

     fun getScore() : Double {
         val bounds = listOf(0, 10, 100, 300, 450, 600, 700, 750, 800, 825)
         val W = if (gamesPlayed >= gamesWon - 1) 1 else 0
         val m = secret!!.length;
         if (m >= bounds.last() * gamesPlayed) {
             return 9f + W.toDouble()
         } else {
             for (i in bounds.size - 2 downTo 0) {
                 if (m > bounds[i] * gamesPlayed) {
                     return i + (m.toDouble() / gamesPlayed - bounds[i]) / (bounds[i + 1] - bounds[i]);
                 }
             }
         }
         throw AssertionError()
     }
}

