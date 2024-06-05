package ua.ldv;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.text.StringEscapeUtils;

public class YmlFileSplitter {

    private static final String API_KEY = "AIzaSyA4bwXT2nXlxsgpBOuzWDv9YNORX0QfXvs";

    // Метод для перекладу тексту на українську мову
    public static String translateToUkrainian(String text) throws IOException {
        String encodedText = URLEncoder.encode(text, "UTF-8");
        String url = String.format(
                "https://translation.googleapis.com/language/translate/v2?key=%s&q=%s&target=uk",
                API_KEY, encodedText
        );

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(URI.create(url));
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                String jsonResponse = EntityUtils.toString(response.getEntity());
                JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
                JsonObject dataObject = jsonObject.getAsJsonObject("data");
                if (dataObject == null) {
                    throw new IOException("Unexpected API response format: " + jsonResponse);
                }
                String translatedText = dataObject.getAsJsonArray("translations")
                        .get(0).getAsJsonObject()
                        .get("translatedText").getAsString();
                return StringEscapeUtils.unescapeHtml4(translatedText); // Розкодовуємо HTML-ентітети
            }
        }
    }

    // Метод для розділення та перекладу значень у YML файлі з багатопоточністю
    public static void translateYmlFile(String inputFile, String outputFile, JLabel timerLabel) throws IOException, InterruptedException, ExecutionException {
        List<String> lines = Files.readAllLines(Paths.get(inputFile));
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new CopyOnWriteArrayList<>();

        boolean firstLine = true;
        for (String line : lines) {
            if (firstLine) {
                // Видаляємо BOM (якщо є) та робимо заміну
                line = line.replace("\uFEFF", "").trim();
                if (line.equals("l_english:")) {
                    line = "l_ukrainian:";
                }
                futures.add(CompletableFuture.completedFuture(line));
                firstLine = false;
                continue;
            }

            if (line.contains(":")) {
                int colonIndex = line.indexOf(':');
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1); // Видалити лапки
                }

                String finalValue = value;
                futures.add(executor.submit(() -> {
                    String formattedLine;
                    if (finalValue.contains("§")) {
                        // Розбити рядок на частини, щоб уникнути перекладу частин у символах §
                        StringBuilder translatedValue = new StringBuilder();
                        String[] parts = finalValue.split("§");
                        for (int i = 0; i < parts.length; i++) {
                            if (i % 2 == 0) {
                                // Частини, які потрібно перекласти
                                translatedValue.append(translateToUkrainian(parts[i]));
                            } else {
                                // Частини, які не потрібно перекладати
                                translatedValue.append("§").append(parts[i]).append("§");
                            }
                        }
                        formattedLine = key + ": \"" + translatedValue + "\"";
                    } else {
                        formattedLine = key + ": \"" + translateToUkrainian(finalValue) + "\"";
                    }
                    return "  " + formattedLine;
                }));
            } else {
                futures.add(CompletableFuture.completedFuture("  " + line));
            }
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputFile))) {
            for (Future<String> future : futures) {
                String futureResult = future.get();
                writer.write(futureResult);
                writer.newLine();
            }
        }
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        // Створюємо вікно для відображення таймера
        JFrame frame = new JFrame("Переклад файлу");
        JLabel timerLabel = new JLabel("Час виконання: 0 сек");
        frame.add(timerLabel);
        frame.setSize(300, 100);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        // Створюємо таймер для оновлення часу виконання
        Timer timer = new Timer(1000, e -> {
            long currentTime = System.currentTimeMillis();
            long duration = currentTime - startTime;
            long hours = (duration / 1000) / 3600;
            long minutes = ((duration / 1000) % 3600) / 60;
            long seconds = (duration / 1000) % 60;
            timerLabel.setText(String.format("Час виконання: %d год %d хв %d сек", hours, minutes, seconds));
        });
        timer.start();

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select a YML file");
        int result = fileChooser.showOpenDialog(null);

        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            String inputFile = selectedFile.getAbsolutePath();
            String outputFile = inputFile.replace("_english", "_ukrainian");

            try {
                translateYmlFile(inputFile, outputFile, timerLabel);
                JOptionPane.showMessageDialog(null, "Файл успішно перекладено.", "Інформація", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException | InterruptedException | ExecutionException e) {
                JOptionPane.showMessageDialog(null, "Помилка при перекладі файлу: " + e.getMessage(), "Помилка", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            System.out.println("No file selected.");
        }

        timer.stop();
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        long hours = (duration / 1000) / 3600;
        long minutes = ((duration / 1000) % 3600) / 60;
        long seconds = (duration / 1000) % 60;

        String executionTime = String.format("Час виконання: %d год %d хв %d сек", hours, minutes, seconds);
        JOptionPane.showMessageDialog(null, executionTime, "Час виконання", JOptionPane.INFORMATION_MESSAGE);
    }
}
