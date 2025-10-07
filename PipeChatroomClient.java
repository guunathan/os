import java.io.*;
import java.util.*;

public class PipeChatroomClient {
    private static final String CONTROL_PIPE = "control_pipe.txt";
    private static final String PIPE_DIR = "pipes/";
    private final String clientId;
    private String username;
    private String replyPipe;
    private volatile boolean running = true;
    public PipeChatroomClient() {
        this.clientId = "client_" + System.currentTimeMillis() + "_" + 
                        (int)(Math.random() * 1000);
        this.replyPipe = PIPE_DIR + clientId + ".txt";
    }
    
    public void start() {
        // Login first
        if (!login()) {
            System.out.println("Login failed. Exiting...");
            return;
        }
        
        printWelcome();
        
        // Start heartbeat thread
        Thread heartbeatThread = new Thread(new HeartbeatSender());
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
        
        Thread inputThread = new Thread(new InputHandler());
        inputThread.start(); 
        Thread outputThread = new Thread(new OutputHandler());
        outputThread.start();
        
        // Wait for threads
        try {
            inputThread.join();
            outputThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    private boolean login() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║        University Chatroom - Login            ║");
        System.out.println("╚════════════════════════════════════════════════╝\n");
        
        System.out.print("Enter your username: ");
        username = scanner.nextLine().trim();
        
        if (username.isEmpty()) {
            username = "User" + (int)(Math.random() * 1000);
            System.out.println("Using random username: " + username);
        }
        
        // Send LOGIN command
        sendCommand("LOGIN", username);
        
        // Wait for confirmation
        try {
            Thread.sleep(500);
            
            // Check if reply pipe was created
            File pipe = new File(replyPipe);
            if (pipe.exists()) {
                System.out.println("\n✓ Login successful!");
                return true;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        return false;
    }
    
    private void printWelcome() {
        System.out.println("\n╔════════════════════════════════════════════════╗");
        System.out.println("║           Welcome, " + String.format("%-28s", username) + "║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println("\nCommands:");
        System.out.println("  JOIN <room>          - Join a chat room (e.g., JOIN #os-lab)");
        System.out.println("  SAY <room> <msg>     - Send message to room");
        System.out.println("  DM <username> <msg>  - Direct message to user");
        System.out.println("  WHO <room>           - List members in room");
        System.out.println("  LEAVE <room>         - Leave a room");
        System.out.println("  QUIT                 - Exit");
        System.out.println("─────────────────────────────────────────────────\n");
    }
    
    // Heartbeat sender - ส่ง heartbeat ทุก 10 วินาที
    class HeartbeatSender implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(10000); // 10 seconds
                    sendCommand("HEARTBEAT");
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
    
    // Input Handler - อ่านคำสั่งจากคีย์บอร์ด
    class InputHandler implements Runnable {
        @Override
        public void run() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            
            while (running) {
                try {
                    System.out.print(username + "> ");
                    String line = reader.readLine();
                    
                    if (line == null) {
                        running = false;
                        break;
                    }
                    
                    if (line.trim().isEmpty()) continue;
                    
                    processInput(line.trim());
                    
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
            
            // Cleanup
            sendCommand("QUIT");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {}
        }
        
        private void processInput(String line) {
            String[] parts = line.split("\\s+", 3);
            String command = parts[0].toUpperCase();
            
            switch (command) {
                case "JOIN":
                    if (parts.length < 2) {
                        System.out.println("Usage: JOIN <room>");
                    } else {
                        sendCommand("JOIN", parts[1]);
                    }
                    break;
                    
                case "SAY":
                    if (parts.length < 3) {
                        System.out.println("Usage: SAY <room> <message>");
                    } else {
                        String[] args = line.split("\\s+", 3);
                        sendCommand("SAY", args[1], args[2]);
                    }
                    break;
                    
                case "DM":
                    if (parts.length < 3) {
                        System.out.println("Usage: DM <username> <message>");
                    } else {
                        String[] args = line.split("\\s+", 3);
                        sendCommand("DM", args[1], args[2]);
                    }
                    break;
                    
                case "WHO":
                    if (parts.length < 2) {
                        System.out.println("Usage: WHO <room>");
                    } else {
                        sendCommand("WHO", parts[1]);
                    }
                    break;
                    
                case "LEAVE":
                    if (parts.length < 2) {
                        System.out.println("Usage: LEAVE <room>");
                    } else {
                        sendCommand("LEAVE", parts[1]);
                    }
                    break;
                    
                case "QUIT":
                    System.out.println("\nDisconnecting...");
                    running = false;
                    break;
                    
                case "HELP":
                    printHelp();
                    break;
                    
                default:
                    System.out.println("Unknown command. Type HELP for available commands.");
            }
        }
        
        private void printHelp() {
            System.out.println("\nAvailable Commands:");
            System.out.println("  JOIN <room>          - Join a chat room");
            System.out.println("  SAY <room> <msg>     - Send message to room");
            System.out.println("  DM <username> <msg>  - Direct message to user");
            System.out.println("  WHO <room>           - List room members");
            System.out.println("  LEAVE <room>         - Leave a room");
            System.out.println("  QUIT                 - Exit\n");
        }
    }
    
    // Output Handler - รับและแสดงข้อความ
    class OutputHandler implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    File pipe = new File(replyPipe);
                    if (!pipe.exists()) {
                        Thread.sleep(100);
                        continue;
                    }
                    
                    BufferedReader reader = new BufferedReader(new FileReader(replyPipe));
                    String line;
                    
                    while ((line = reader.readLine()) != null && running) {
                        if (line.trim().isEmpty()) continue;
                        
                        // Clear current input line and print message
                        System.out.print("\r" + " ".repeat(60) + "\r");
                        
                        // Color coding for different message types
                        if (line.startsWith("SYSTEM:")) {
                            System.out.println("💬 " + line);
                        } else if (line.startsWith("[DM from")) {
                            System.out.println("📨 " + line);
                        } else if (line.startsWith("[DM to")) {
                            System.out.println("📤 " + line);
                        } else if (line.startsWith("ERROR:")) {
                            System.out.println("❌ " + line);
                        } else {
                            System.out.println(line);
                        }
                        
                        System.out.print(username + "> ");
                        System.out.flush();
                    }
                    
                    reader.close();
                    
                    // Clear pipe content
                    new PrintWriter(replyPipe).close();
                    
                    Thread.sleep(100); // Polling interval
                    
                } catch (IOException | InterruptedException e) {
                    if (running) {
                        System.err.println("Error reading reply pipe: " + e.getMessage());
                    }
                }
            }
        }
    }
    
    // ส่งคำสั่งไปยัง server ผ่าน control pipe
    private void sendCommand(String command, String... args) {
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(CONTROL_PIPE, true));
            
            StringBuilder sb = new StringBuilder();
            sb.append(clientId).append("|").append(command);
            
            if (args.length > 0) {
                sb.append("|");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append("|");
                    sb.append(args[i]);
                }
            }
            
            writer.println(sb.toString());
            writer.flush();
            writer.close();
            
        } catch (IOException e) {
            System.err.println("Failed to send command: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        // Check if server is running
        File controlPipe = new File(CONTROL_PIPE);
        if (!controlPipe.exists()) {
            System.err.println("╔════════════════════════════════════════════════╗");
            System.err.println("║              ERROR: Server Not Found           ║");
            System.err.println("╚════════════════════════════════════════════════╝");
            System.err.println("\nPlease start the server first:");
            System.err.println("  java PipeChatroomServer\n");
            return;
        }
        
        PipeChatroomClient client = new PipeChatroomClient();
        client.start();
    }
}