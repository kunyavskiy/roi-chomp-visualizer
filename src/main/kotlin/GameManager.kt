import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
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
    private val needDelays: Boolean,
    private val gameSpeed: MutableState<Float>,
    private val streams: Pair<InputStream, OutputStream>?
) {
    val ready = mutableStateOf(false)
    val inputFileName = "game.out"
    val outputFileName = "game.in"
    val columnHeights = Array(fieldSize) { mutableStateOf(fieldSize) }
    var lastMove: Pair<Int, Int>? = null
    val gameLog = mutableStateOf<String?>(null)
    var secret: String? = null
    val gameError = mutableStateOf<String?>(null)
    var gamesPlayed = 0
    var gamesWon = 0
    val visualLog = mutableStateListOf<AnnotatedString>()
    private var input: BufferedReader? = null
    private var output: PrintWriter? = null
    private val rnd = Random(239)
    private val gameLogArray = mutableListOf<Pair<Int, Int>>()

    fun isOkMove(x: Int, y: Int): Boolean {
        return columnHeights[x].value > y
    }

    suspend fun runGame() {
        if (streams != null) {
            input = streams.first.bufferedReader()
            output = PrintWriter(streams.second)
        } else {
            withContext(Dispatchers.IO) {
                launch {
                    output = PrintWriter(createOutputPipe(outputFileName))
                }
                launch {
                    input = createInputPipe(inputFileName).bufferedReader()
                }
            }
        }
        ready.value = true
        withContext(Dispatchers.IO) {
            try {
                val output = output!!
                val input = input!!
                output.println("1")
                output.println("$fieldSize $maxEatenByRandom $secretLength")
                secret = Array(secretLength) { rnd.nextInt(2) }.joinToString("")
                output.println(secret!!)
                output.flush()
                logHeader("???????????????????????? ????????????:")
                log(
                    AnnotatedString(
                        if (secret!!.length <= 15) secret!! else secret!!.substring(0, 15) + "...",
                        spanStyle = SpanStyle(fontFamily = FontFamily.Monospace)
                    )
                )
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
                    lastMove = null
                }
                val builder = StringBuilder()
                builder.append("2").append(System.lineSeparator())
                builder.append("$fieldSize $maxEatenByRandom $secretLength").append(System.lineSeparator())
                builder.append(games).append(System.lineSeparator())
                builder.append(gameLogArray.joinToString(System.lineSeparator()) { "${it.first} ${it.second}" })
                gameLog.value = builder.toString()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    gameError.value = e.message ?: "?????????????????????? ????????????"
                } else {
                    cleanupPipeOnCancel(inputFileName)
                    cleanupPipeOnCancel(outputFileName)
                }
            } finally {
                input?.close()
                output?.close()
            }
        }
    }

    private fun log(s: AnnotatedString) {
        if (needDelays) {
            visualLog.add(s)
        }
    }

    private fun log(s: String) {
        log(AnnotatedString(s))
    }

    private fun logHeader(s: String) {
        log(AnnotatedString(s, spanStyle = SpanStyle(fontWeight = FontWeight.Bold)))
    }

    private fun logIn(s: String) {
        log(
            AnnotatedString(
                s,
                spanStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = Color.Blue),
                paragraphStyle = ParagraphStyle(textAlign = TextAlign.Right)
            )
        )
    }

    private fun logOut(s: String) {
        log(
            AnnotatedString(
                s,
                spanStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = Color.Red)
            )
        )
    }

    private suspend fun processMove(x: Int, y: Int) {
        drawMutex.withLock {
            if (!isOkMove(x, y)) {
                throw BadMoveException("???????????????????? ??????: ???????????? (${x + 1}, ${y + 1}) ?????? ??????????????????")
            }
            for (i in x until fieldSize) {
                columnHeights[i].value = minOf(columnHeights[i].value, y)
            }
            lastMove = Pair(x, y)
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
        logHeader("?????????? ????????")
        if (firstMove) {
            log("???????????? ???????????? ????")
        } else {
            log("???????????? ?????????? ??????")
        }
        log("????????-??????????")

        var move = firstMove
        var firstPlayerMove = true
        while (columnHeights[0].value > 0) {
            if (move) {
                val line = input.readLine() ?: throw BadMoveException("?????????????????????? ?????????? ???????????? ??????????????")
                logIn(line)
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
                        throw BadMoveException("?????????????????? ?????? ?????????? ???? 1 ???? $fieldSize, ?? ?????????????? ???????????? $x $y")
                    }
                    processMove(x - 1, y - 1)
                } catch (_: NumberFormatException) {
                    throw BadMoveException("?????????????????? ?????? ??????????, ?? ?????????????? ???????????? \"$line\"")
                }
            } else {
                val randomMove = sequence {
                    for (x in 0 until fieldSize) {
                        for (y in columnHeights[x].value - 1 downTo 0) {
                            if ((x == 0 && y == 0) || eatenMoreThan(x, y, maxEatenByRandom)) break
                            yield(Pair(x, y))
                        }
                    }
                }.foldIndexed(Pair(0, 0), { i, acc, value -> if (rnd.nextInt(i + 1) == 0) value else acc })
                processMove(randomMove.first, randomMove.second)
                val line = "${randomMove.first + 1} ${randomMove.second + 1}"
                output.println(line)
                output.flush()
                logOut(line)
            }
            if (needDelays) {
                delay((1000.0 / gameSpeed.value).toLong())
            }
            move = !move
        }
        gamesPlayed += 1
        if (move) {
            gamesWon += 1
        }
        return true
    }

    fun getScore(): Double {
        val bounds = listOf(0, 1, 100, 300, 450, 600, 700, 750, 800, 825)
        val w = if (gamesPlayed >= gamesWon - 1) 1 else 0
        val m = secret!!.length
        if (m >= bounds.last() * gamesPlayed) {
            return 9f + w.toDouble()
        } else {
            for (i in bounds.size - 2 downTo 0) {
                if (m > bounds[i] * gamesPlayed) {
                    return i + (m.toDouble() / gamesPlayed - bounds[i]) / (bounds[i + 1] - bounds[i])
                }
            }
        }
        throw AssertionError()
    }
}

