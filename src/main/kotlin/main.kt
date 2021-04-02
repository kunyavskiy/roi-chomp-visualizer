@file:Suppress("FunctionName")

import androidx.compose.desktop.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import javax.swing.JFileChooser

fun main() = visualizerMain()

@Composable
fun TextFieldWithChooseFileButton(label: String, filePath: MutableState<String?>) {
    Row {
        TextField(
            filePath.value ?: "",
            onValueChange = { filePath.value = it },
            singleLine = true,
            label = { Text(label) },
        )
        Button(
            onClick = {
                var result: File?
                JFileChooser(File(".")).apply {
                    showOpenDialog(null)
                    result = selectedFile
                }
                result?.apply {
                    filePath.value = relativeTo(File(".").canonicalFile).path
                }
            }
        ) { Text("...") }
    }
}

@Composable
fun IntTextField(state: MutableState<String>, label: String) {
    TextField(
        state.value,
        onValueChange = { state.value = it },
        singleLine = true,
        isErrorValue = !(state.value.toIntOrNull() != null),
        label = { Text(label) }
    )
}

@Composable
fun ConstTextField(value: String) {
    TextField(
        value,
        onValueChange = { },
    )
}

fun visualizerMain() = Window(title = "Визуализатор для задачи «Игра с тайным смыслом»", size = IntSize(600, 600)) {
    val game = remember { mutableStateOf<GameManager?>(null) }
    val fieldSize = remember { mutableStateOf("32") }
    val maxEaten = remember { mutableStateOf("5") }
    val secretLength = remember { mutableStateOf("100") }
    val drawMutex = remember { Mutex() }
    val needDrawGame = remember { mutableStateOf(false) }
    val logFilePath = remember { mutableStateOf<String?>(null) }
    val secretFilePath = remember { mutableStateOf<String?>(null) }
    val errorMessage = remember { mutableStateOf<String?>(null) }

    runBlocking {
        drawMutex.lock()
    }
    MaterialTheme {
        if (errorMessage.value != null) {
            AlertDialog(
                onDismissRequest = { errorMessage.value = null },
                text = { errorMessage.value?.apply { Text(this@apply) } },
                title = { Text("Ошибка") },
                buttons = {}
            )
        }
        Column {
            if (game.value == null) {
                IntTextField(fieldSize, "Размер поля")
                IntTextField(maxEaten, "Максимум клеток за ход бота")
                IntTextField(secretLength, "Длина очень важного секрета")
                Button(
                    {
                        if (game.value != null) return@Button
                        game.value = GameManager(
                            fieldSize.value.toInt(),
                            maxEaten.value.toInt(),
                            secretLength.value.toInt(),
                            drawMutex,
                            needDrawGame.value
                        )
                        GlobalScope.launch { game.value?.runGame() }
                    },
                    enabled = sequenceOf(fieldSize, maxEaten, secretLength).all { it.value.toIntOrNull() != null }
                ) { Text("Начать игру") }
                Row {
                    Checkbox(
                        needDrawGame.value,
                        { needDrawGame.value = it }
                    )
                    Text("Визуализировать игру", modifier = Modifier.clickable { needDrawGame.value = !needDrawGame.value })
                }
            } else {
                game.value?.apply {
                    if (!ready.value) {
                        val outputName = getPipePrefix() + outputFileName
                        val inputName = getPipePrefix() + inputFileName
                        Text(" Ожидание решения")
                        Text(" Решение должно считывать из файла: $outputName")
                        Text(" Решение должно выводить в файл: $inputName")
                        Text(" Пример кода для открытия файлов для:")
                        val languages = listOf("C++", "Java", "Python")
                        val selectedLanguage = remember { mutableStateOf(languages[0]) }
                        languages.forEach {
                            Row {
                                RadioButton(
                                    onClick = { selectedLanguage.value = it },
                                    selected = selectedLanguage.value == it
                                )
                                Text(it)
                            }
                        }
                        val outputFileNameEncoded = outputName.replace("\\", "\\\\")
                        val inputFileNameEncoded = inputName.replace("\\", "\\\\")
                        when (selectedLanguage.value) {
                            "C++" -> {
                                ConstTextField(
                                    """
                                        freopen("$outputFileNameEncoded", "r", stdin);
                                        freopen("$inputFileNameEncoded", "w", stdout);
                                    """.trimIndent()
                                )
                                Text("или")
                                ConstTextField(
                                    """
                                        ifstream in("$outputFileNameEncoded");
                                        ofstream out("$inputFileNameEncoded");
                                    """.trimIndent()
                                )
                            }
                            "Java" -> {
                                ConstTextField(
                                    """
                                        File inputFile = new File("$outputFileNameEncoded");
                                        File outputFile = new File("$inputFileNameEncoded");
                                        Scanner in = new Scanner(inputFile);
                                        PrintWriter out = new PrintWriter(outputFile); 
                                    """.trimIndent()
                                )
                            }
                            "Python" -> {
                                ConstTextField(
                                    """
                                        inputFile = open("$outputFileNameEncoded", "r");
                                        outputFile = open("$inputFileNameEncoded", "w");
                                    """.trimIndent()
                                )

                            }
                        }
                    } else if (gameError.value != null) {
                        errorMessage.value = gameError.value
                        game.value = null
                    } else if (gameLog.value != null) {
                        TextFieldWithChooseFileButton("Путь для сохранения лога", logFilePath)
                        TextFieldWithChooseFileButton("Путь для сохранения секрета", secretFilePath)
                        Row {
                            Button(
                                onClick = {
                                    try {
                                        File(logFilePath.value!!).printWriter().use {
                                            it.println(gameLog.value)
                                        }
                                        File(secretFilePath.value!!).printWriter().use {
                                            it.println(secret)
                                        }
                                    } catch (e: Exception) {
                                        errorMessage.value = e.message
                                    }
                                    game.value = null
                                },
                                enabled = logFilePath.value != null && secretFilePath.value != null
                            ) { Text("Сохранить") }
                            Button({ game.value = null }) { Text("Не сохранять") }
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

private fun GameManager.drawGameState(canvas: DrawScope) = with(canvas) {
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
    } catch (_: Exception) {
    }
}
