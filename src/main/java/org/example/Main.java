package org.example;

import java.io.*;
import java.net.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Map<String, ClientHandler> clients = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // Загружаем конфигурацию из файла config.properties
        Properties properties = new Properties();
        InputStream input = Main.class.getClassLoader().getResourceAsStream("config.properties");
        if (input == null) {
            System.err.println("Properties file not found.");
            return;
        }
        properties.load(input);

        String host = properties.getProperty("host", "0.0.0.0");
        int port = Integer.parseInt(properties.getProperty("port"));

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(host, port));
        System.out.println("Server starting on " + host + ":" + port);
        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("New client connected: " + clientSocket.getInetAddress());
            new Thread(new ClientHandler(clientSocket)).start();
        }
    }

    public static class ClientHandler implements Runnable {
        private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);
        private final Socket socket;
        private String nickname;
        private BufferedReader in;
        private BufferedWriter out;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

                // Запрашиваем никнейм
                out.write("Enter your nickname: ");
                out.flush();
                nickname = in.readLine();

                // Проверка и регистрация никнейма
                synchronized (clients) {
                    if (nickname == null || nickname.trim().isEmpty() || clients.containsKey(nickname)) {
                        out.write("Nickname is invalid or already taken. Try again.\n");
                        out.flush();
                        return;
                    }
                    clients.put(nickname, this);
                }

                logger.info("User '{}' joined the chat.", nickname);
                sendBroadcast("Server", nickname + " joined the chat.");

                // Обработка сообщений
                String message;
                while ((message = in.readLine()) != null) {
                    logger.info("Received message from '{}': {}", nickname, message);
                    processMessage(message);
                }
            } catch (IOException e) {
                logger.error("Error with client '{}': {}", nickname, e.getMessage());
            } finally {
                disconnect();
            }
        }

        private void processMessage(String message) throws IOException {
            if (message == null || message.trim().isEmpty()) {
                return;
            }

            String[] parts = message.split(":", 2);
            if (parts.length < 2) {
                out.write("Invalid message format. Use: TYPE:CONTENT\n");
                out.flush();
                return;
            }

            String type = parts[0].toUpperCase();
            String content = parts[1];

            switch (type) {
                case "BROADCAST":
                    sendBroadcast(nickname, content);
                    logger.info("Broadcast message from '{}': {}", nickname, content);
                    break;
                case "PRIVATE":
                    String[] privateParts = content.split(":", 2);
                    if (privateParts.length < 2) {
                        out.write("Invalid private message format. Use: PRIVATE:recipient:message\n");
                        out.flush();
                        return;
                    }
                    String recipient = privateParts[0];
                    String privateMessage = privateParts[1];
                    sendPrivate(nickname, recipient, privateMessage);
                    logger.info("Private message from '{}' to '{}': {}", nickname, recipient, privateMessage);
                    break;
                case "LIST":
                    sendUserList();
                    logger.info("Sent user list to '{}': {}", nickname, String.join(", ", clients.keySet()));
                    break;
                default:
                    out.write("Unknown message type.\n");
                    out.flush();
            }
        }

        private void sendBroadcast(String sender, String message) {
            synchronized (clients) {
                clients.values().forEach(client -> {
                    try {
                        client.out.write(sender + " (to all): " + message + "\n");
                        client.out.flush();
                    } catch (IOException e) {
                        logger.error("Error sending broadcast message: {}", e.getMessage());
                    }
                });
            }
        }

        private void sendPrivate(String sender, String recipient, String message) throws IOException {
            synchronized (clients) {
                ClientHandler client = clients.get(recipient);
                if (client != null) {
                    client.out.write(sender + " (private): " + message + "\n");
                    client.out.flush();
                } else {
                    out.write("User " + recipient + " not found.\n");
                    out.flush();
                }
            }
        }

        private void sendUserList() throws IOException {
            synchronized (clients) {
                String userList = "Connected users: " + String.join(", ", clients.keySet()) + "\n";
                out.write(userList);
                out.flush();
            }
        }

        private void disconnect() {
            try {
                synchronized (clients) {
                    clients.remove(nickname);
                }
                logger.info("User '{}' disconnected.", nickname);
                sendBroadcast("Server", nickname + " left the chat.");
                socket.close();
            } catch (IOException e) {
                logger.error("Error disconnecting client '{}': {}", nickname, e.getMessage());
            }
        }
    }
}
