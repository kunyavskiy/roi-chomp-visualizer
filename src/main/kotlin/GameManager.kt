import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.lang.StringBuilder
import kotlin.random.Random

import com.sun.jna.Platform;
import java.io.*
import java.lang.Exception

fun pipePrefix() : String {
    if (Platform.isWindows()) {
        return "\\\\.pipe\\"
    } else {
        return "/tmp/"
    }
}

class GameManager(
    val fieldSize: Int,
    val maxEatenByRandom: Int,
    val secretLength: Int,
    val drawMutex: Mutex,
    val needDealys: Boolean
) {
    val ready = mutableStateOf(false)
    var input: BufferedReader? = null
    var output: PrintWriter? = null
    val inputFileName = pipePrefix() + "game.out"
    val outputFileName = pipePrefix() + "game.in"
    val columnHeights = Array(fieldSize) { mutableStateOf(fieldSize) }
    val rnd = Random(239)
    val gameLog = mutableStateOf<String?>(null)
    var secret:String? = null
    val gameLogArray = mutableListOf<Pair<Int, Int>>()
    val gameError = mutableStateOf<String?>(null)

    suspend fun run() {
        System.err.println("Start waiting for solution")
        coroutineScope {
            if (Platform.isWindows()) {
                TODO("Create pipes!!!")
            } else {
                if (!File(inputFileName).exists()) {
                    Runtime.getRuntime().exec("mkfifo " + inputFileName)
                }
                if (!File(outputFileName).exists()) {
                    Runtime.getRuntime().exec("mkfifo " + outputFileName)
                }
            }
            launch {
                withContext(Dispatchers.IO) {
                    output = File(outputFileName).printWriter()
                }
            }
            launch {
                withContext(Dispatchers.IO) {
                    input = File(inputFileName).bufferedReader()
                }
            }
        }
        ready.value = true
        System.err.println("Init is done")
        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val output = output!!
                    val input = input!!
                    output.println("1 ${fieldSize} ${maxEatenByRandom} ${secretLength}");
                    secret = Array(secretLength) { rnd.nextInt(2) }.joinToString("")
                    output.println(secret)
                    output.flush()
                    var firstMove = true
                    var games = 0
                    while (runOneGame(input, output, firstMove)) {
                        games += 1
                        firstMove = !firstMove
                        runBlocking {
                            drawMutex.lock()
                            for (i in 0 until fieldSize) {
                                columnHeights[i].value = fieldSize
                            }
                            drawMutex.unlock()
                        }
                    }
                    val builder = StringBuilder()
                    builder.append("2 ${fieldSize} ${maxEatenByRandom} ${secretLength}").append(System.lineSeparator())
                    builder.append(games).append(System.lineSeparator())
                    builder.append(gameLogArray.joinToString(System.lineSeparator()) { "${it.first} ${it.second}" })
                    gameLog.value = builder.toString()
                } catch (e : Exception) {
                    gameError.value = e.message ?: "Неизвестная ошибка"
                }
            }
        }
    }

    suspend fun processMove(x: Int, y: Int) {
        drawMutex.lock()
        for (i in x until fieldSize) {
            columnHeights[i].value = minOf(columnHeights[i].value, y)
        }
        drawMutex.unlock()
        gameLogArray.add(Pair(x + 1, y + 1))
    }

    fun countEaten(x: Int, y: Int): Int {
        var ans = 0
        for (i in x until fieldSize) {
            ans += maxOf(0, columnHeights[i].value - (y - 1))
        }
        return ans
    }

    suspend fun runOneGame(input: BufferedReader, output: PrintWriter, firstMove: Boolean): Boolean {
        System.err.println("Starting new game")
        var move = firstMove
        var any = false
        while (columnHeights[0].value > 0) {
            if (move) {
                val line = input.readLine() ?: throw RuntimeException("Неожиданный конец ввода")
                if (!any && line.isInt() && line.toInt() == 0) {
                    if (!firstMove) {
                        gameLogArray.removeLast()
                    }
                    return false;
                }
                any = true
                val (x, y) = line.split(" ").map { it.toInt() }
                processMove(x - 1, y - 1)
            } else {
                val randomMove =
                    (0 until fieldSize).flatMap { lhs -> (0 until fieldSize).map { Pair(lhs, it) } }.filter {
                        it.second < columnHeights[it.first].value && maxOf(
                            it.first,
                            it.second
                        ) > 0 && countEaten(it.first, it.second) <= maxEatenByRandom
                    }.randomOrNull(rnd) ?: Pair(0, 0)
                processMove(randomMove.first, randomMove.second)
                output.println("${randomMove.first + 1} ${randomMove.second + 1}")
                output.flush()
                if (needDealys) {
                    delay(30)
                }
            }
            move = !move
        }
        return true
    }
}

