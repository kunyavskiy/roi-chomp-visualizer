import androidx.compose.desktop.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.PrintWriter

fun String.isInt() = try {
    toInt(); true; } catch (ignored: NumberFormatException) {
    false; }

fun main() = visualizerMain()

fun visualizerMain() = Window(title = "Визуализатор для задачи Игра с тайным смыслом", size = IntSize(600, 600)) {
    val game = remember { mutableStateOf<GameManager?>(null) }
    val fieldSize = remember { mutableStateOf("32") }
    val isFieldSizeValid = fieldSize.value.isInt()
    val maxEaten = remember { mutableStateOf("5") }
    val isMaxEatenValid = maxEaten.value.isInt()
    val secretLength = remember { mutableStateOf("100") }
    val isSecretLengthValid = secretLength.value.isInt()
    val drawMutex = remember { Mutex() }
    val needDrawGame = remember { mutableStateOf(false) }
    val filePath = remember { mutableStateOf("") }

    runBlocking {
        drawMutex.lock()
    }
    MaterialTheme {
        Column {
            if (game.value == null) {
                TextField(
                    fieldSize.value,
                    onValueChange = { fieldSize.value = it },
                    singleLine = true,
                    isErrorValue = !isFieldSizeValid,
                    label = { Text("Размер поля") }
                )
                TextField(
                    maxEaten.value,
                    onValueChange = { maxEaten.value = it },
                    singleLine = true,
                    isErrorValue = !isMaxEatenValid,
                    label = { Text("Максимум клеток за ход бота") }
                )
                TextField(
                    secretLength.value,
                    onValueChange = { secretLength.value = it },
                    singleLine = true,
                    isErrorValue = !isSecretLengthValid,
                    label = { Text("Длина очень важного секерета") }
                )
                Button(
                    {
                        if (game.value == null) {
                            game.value = GameManager(
                                fieldSize.value.toInt(),
                                maxEaten.value.toInt(),
                                secretLength.value.toInt(),
                                drawMutex,
                                needDrawGame.value
                            )
                            GlobalScope.launch { game.value?.run() }
                        }
                    },
                    enabled = game.value == null && isFieldSizeValid && isMaxEatenValid && isSecretLengthValid
                ) { Text("Начать игру") }
                Row {
                    Checkbox(
                        needDrawGame.value,
                        { needDrawGame.value = it }
                    )
                    Text("Визуализировать игру")
                }
            } else {
                game.value?.apply {
                    if (!ready.value) {
                        Text("Ожидание решения")
                        Text("Решение должно считывать из файла:" + outputFileName)
                        Text("Решение должно выводить в файл:" + inputFileName)
                    } else if (gameLog.value != null) {
                        TextField(
                            filePath.value,
                            onValueChange = { filePath.value = it },
                            singleLine = true,
                            label = { Text("Путь для сохранения лога") }
                        )
                        Row {
                            Button({
                                val writer = PrintWriter(File(filePath.value))
                                writer.println(gameLog.value)
                                writer.close()
                                game.value = null
                            }) { Text("Сохранить") }
                            Button({ game.value = null }) { Text("Не Сохранять") }
                        }
                    } else if (needDrawGame.value) {
                        Canvas(Modifier.fillMaxSize()) {
                            this@apply.drawGameState(this)
                        }
                    } else {
                        Text("Ожидаем работы решения")
                    }
                }
            }
        }
    }
    drawMutex.unlock()
}

private fun GameManager.drawGameState(canvas: DrawScope) = with (canvas) {
    System.err.println("redraw")
    val n = fieldSize
    try {
        for (i in 0..n) {
            drawLine(
                start = Offset(0f, size.height / n * i),
                end = Offset(size.width, size.height / n * i),
                color = Color.Black
            )
            drawLine(
                start = Offset(size.width / n * i, 0f),
                end = Offset(size.width / n * i, size.height),
                color = Color.Black
            )
        }
        for (i in 0 until n) {
            val rows = columnHeights[i].value
            if (rows != n) {
                val height = size.height / n * (n - rows)
                drawRect(
                    color = Color.LightGray,
                    topLeft = Offset(
                        x = size.width / n * i,
                        y = 0f
                    ),
                    size = Size(
                        width = size.width / n,
                        height = height
                    )
                )
            }
        }
        for (i in 0..n) {
            drawLine(
                start = Offset(0f, size.height / n * i),
                end = Offset(size.width, size.height / n * i),
                color = Color.Black
            )
            drawLine(
                start = Offset(size.width / n * i, 0f),
                end = Offset(size.width / n * i, size.height),
                color = Color.Black
            )
        }
    } catch (ignored: Exception) {
    }
}
