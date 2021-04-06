@file:Suppress("FunctionName")

import androidx.compose.desktop.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.sync.*
import java.io.*
import java.lang.Math.floor
import javax.swing.*

fun main() = visualizerMain()

@Composable
fun TextFieldWithChooseFileButton(label: String, filePath: MutableState<String?>) {
    Row (verticalAlignment = Alignment.CenterVertically){
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
        isError = state.value.toIntOrNull() == null,
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

@Composable
fun CheckBoxWithText(state: MutableState<Boolean>, label: String) {
    Row {
        Checkbox(
            state.value,
            { state.value = it }
        )
        Text(label, modifier = Modifier.clickable { state.value = !state.value })
    }
}


fun visualizerMain() = Window(title = "Визуализатор для задачи «Игра с тайным смыслом»", size = IntSize(600, 800)) {
    val game = remember { mutableStateOf<GameManager?>(null) }
    val fieldSize = remember { mutableStateOf("32") }
    val maxEaten = remember { mutableStateOf("8") }
    val secretLength = remember { mutableStateOf("100") }
    val drawMutex = remember { Mutex() }
    val needDrawGame = remember { mutableStateOf(true) }
    val playByHand = remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val gameSpeed = remember { mutableStateOf(60f) }
    var clickerJob by remember { mutableStateOf<Job?>(null) }
    var clickerChannel by remember { mutableStateOf<Channel<Pair<Int, Int>>?>(null) }

    fun stopGame() {
        clickerChannel?.close()
        clickerJob?.cancel()
        job?.cancel()
        runBlocking {
            clickerJob?.join()
            job?.join()
        }
        errorMessage = null
    }

    fun createInternalPipes() : Pair<Pair<InputStream, OutputStream>?, Pair<InputStream, OutputStream>?> {
        if (playByHand.value) {
            val pipe1_in = PipedInputStream()
            val pipe1_out = PipedOutputStream(pipe1_in)
            val pipe2_in = PipedInputStream()
            val pipe2_out = PipedOutputStream(pipe2_in)
            return (pipe1_in to pipe2_out) to (pipe2_in to pipe1_out)
        } else {
            return null to null
        }
    }

    fun startNewGame() {
        stopGame()
        val (gamePipes, playerPipes) = createInternalPipes()
        game.value = GameManager(
            fieldSize.value.toInt(),
            maxEaten.value.toInt(),
            secretLength.value.toInt(),
            drawMutex,
            needDrawGame.value,
            gameSpeed,
            gamePipes
        )
        job = GlobalScope.launch { game.value?.runGame() }
        if (playByHand.value) {
            clickerJob = GlobalScope.launch {
                clickerChannel = Channel()
                ClickerSolution(clickerChannel!!, playerPipes!!.first, playerPipes!!.second).work()
            }
        }
    }

    runBlocking {
        drawMutex.lock()
    }
    MaterialTheme {
        Column {
            if (game.value == null || game.value!!.gameLog.value != null) {
                IntTextField(fieldSize, "Размер поля")
                IntTextField(maxEaten, "Максимум клеток за ход бота")
                IntTextField(secretLength, "Длина очень важного секрета")
                Button(
                    {
                        startNewGame()
                    },
                    enabled = sequenceOf(fieldSize, maxEaten, secretLength).all { it.value.toIntOrNull() != null }
                ) { Text("Начать игру") }
                CheckBoxWithText(needDrawGame,"Визуализировать игру")
                CheckBoxWithText(playByHand,"Играть руками")
            }
            game.value?.apply {
                if (!ready.value) {
                    val outputName = getPipePrefix() + outputFileName
                    val inputName = getPipePrefix() + inputFileName
                    Text(" Ожидание решения")
                    Text(" Решение должно считывать из файла: $outputName")
                    Text(" Решение должно выводить в файл: $inputName")
                    Text(" Пример кода для открытия файлов для:")
                    val languages = listOf("C++", "Java", "Python", "Pascal", "C#")
                    var selectedLanguage by remember { mutableStateOf(languages[0]) }
                    languages.forEach {
                        Row {
                            RadioButton(
                                onClick = { selectedLanguage = it },
                                selected = selectedLanguage == it
                            )
                            Text(it)
                        }
                    }
                    val outputFileNameEncoded = outputName.replace("\\", "\\\\")
                    val inputFileNameEncoded = inputName.replace("\\", "\\\\")
                    when (selectedLanguage) {
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
                                        sys.stdin = open("$outputFileNameEncoded", "r");
                                        sys.stdout = open("$inputFileNameEncoded", "w");
                                    """.trimIndent()
                            )
                        }
                        "Pascal" -> {
                            ConstTextField(
                                """
                                        AssignFile(input, '$outputName');
                                        Reset(input);
                                        AssignFile(output, '$inputName');
                                        Rewrite(output);
                                    """.trimIndent()
                            )
                        }
                        "C#" -> {
                            ConstTextField(
                                """
                                        var pipeIn = new NamedPipeClientStream(".", "game.in", PipeDirection.In);
                                        pipeIn.Connect();
                                        StreamReader reader = new StreamReader(pipeIn);
                                        var pipeOut = new NamedPipeClientStream(".", "game.out", PipeDirection.Out);
                                        pipeOut.Connect();
                                        StreamWriter writer = new StreamWriter(pipeOut);
                                    """.trimIndent()
                            )
                        }
                    }
                } else if (gameError.value != null) {
                    errorMessage = gameError.value
                    gameError.value = null
                } else if (gameLog.value != null) {
                    val played = game.value!!.gamesPlayed
                    val won = game.value!!.gamesWon
                    Text("Сыграно игр: $played")
                    Text("Выиграно игр: $won")
                    if (gamesPlayed > 1) {
                        Text("В среднем переданно бит за игру: ${secretLength.value.toDouble() / gamesPlayed}")
                        Text("Баллов за тест: ${game.value!!.getScore()}")
                    }
                    val logFilePath = remember { mutableStateOf<String?>(null) }
                    val secretFilePath = remember { mutableStateOf<String?>(null) }
                    TextFieldWithChooseFileButton("Путь для сохранения лога", logFilePath)
                    TextFieldWithChooseFileButton("Путь для сохранения секрета", secretFilePath)
                    val fileSaveError = remember { mutableStateOf<String?>(null) }
                    Button(
                        onClick = {
                            try {
                                File(logFilePath.value!!).printWriter().use {
                                    it.println(gameLog.value)
                                }
                                File(secretFilePath.value!!).printWriter().use {
                                    it.println(secret)
                                }
                                game.value = null
                                fileSaveError.value = null
                            } catch (e: Exception) {
                                fileSaveError.value = e.message
                            }
                        },
                        enabled = logFilePath.value != null && secretFilePath.value != null
                    ) { Text("Сохранить") }
                    fileSaveError.value?.apply { Text("Ошибка сохранения: $this") }
                } else if (needDrawGame.value || errorMessage != null) {
                    Canvas(
                        Modifier.size(Dp(600f), Dp(600f)).pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    runBlocking {
                                        val n = fieldSize.value.toInt()
                                        val cellSizeX = size.width.toDouble() / n
                                        val cellSizeY = size.height.toDouble() / n
                                        val xCell = floor(offset.x.toDouble() / cellSizeX).toInt() + 1
                                        val yCell = n - floor(offset.y.toDouble() / cellSizeY).toInt()
                                        clickerChannel?.send(Pair(xCell, yCell))
                                    }
                                }
                            )
                        }
                    ) {
                        this@apply.drawGameState(this)
                    }
                    Column {
                        Row {
                            Slider(
                                value = gameSpeed.value,
                                valueRange = 1f..200f,
                                onValueChange = { gameSpeed.value = it },
                                modifier = Modifier.weight(0.7f)
                            )
                            Button({
                                startNewGame()
                            }) { Text("Начать заново") }
                            if (playByHand.value) {
                                Button({
                                    runBlocking {
                                        clickerChannel!!.send(Pair(-1, -1))
                                    }
                                }) { Text("Закончить игру") }
                            } else {
                                Button({
                                    stopGame()
                                    game.value = null
                                }) { Text("Остановить игру") }
                            }
                        }
                        errorMessage?.apply { Text("Ошибка: $this") }
                    }
                } else {
                    Text("Ожидаем работы решения")
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
