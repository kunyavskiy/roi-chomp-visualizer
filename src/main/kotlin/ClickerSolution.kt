import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.io.*

@Suppress("BlockingMethodInNonBlockingContext")
class ClickerSolution(
    private val clicks: Channel<Pair<Int, Int>>,
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    suspend fun work() {
        try {
            withContext(Dispatchers.IO) {
                inputStream.bufferedReader().use { input ->
                    outputStream.bufferedWriter().use { output ->
                        input.readLine() // 1
                        input.readLine() // n k m
                        input.readLine() // secret
                        var firstMove = true
                        while (true) {
                            var move = firstMove
                            while (true) {
                                val (x, y) = if (move) {
                                    clicks.receive().also {
                                        if (it.first == -1) {
                                            output.write("0")
                                            output.newLine()
                                            output.flush()
                                            return@withContext
                                        }
                                        output.write("${it.first} ${it.second}")
                                        output.newLine()
                                        output.flush()
                                    }.toList()
                                } else {
                                    input.readLine().split(" ").map { it.toInt() }
                                }
                                move = !move
                                if (x == 1 && y == 1) {
                                    break
                                }
                            }
                            firstMove = !firstMove
                        }
                    }
                }
            }
        } catch (_: Exception) {

        }
    }
}