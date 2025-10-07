import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class PipeChatroomServer {
    private static final String CONTROL_PIPE = "control_pipe.txt";
    private static final String PIPE_DIR = "pipes/";
    private static final Map<String, Set<String>> roomRegistry = new ConcurrentHashMap<>();
    private static final Map<String, ClientInfo> clientRegistry = new ConcurrentHashMap<>();
    private static final BlockingQueue<BroadcastTask> broadcastQueue = new LinkedBlockingQueue<>();
    private static final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    
    private static volatile boolean running = true;
    private static final int NUM_BROADCASTERS = 4;
    private static final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private static final long HEARTBEAT_TIMEOUT = 30000; 
    
    static class ClientInfo {
        String clientId;
        String username;
        String replyPipe;
        long lastSeen;
        
        ClientInfo(String clientId, String username, String replyPipe) {
            this.clientId = clientId;
            this.username = username;
            this.replyPipe = replyPipe;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    static class Message {
        String clientId;
        String command;
        String[] args;
        
        Message(String clientId, String command, String[] args) {
            this.clientId = clientId;
            this.command = command;
            this.args = args;
        }
    }
    
    static class BroadcastTask {
        String room;
        String message;
        String senderId;
        boolean excludeSender;
        
        BroadcastTask(String room, String message, String senderId, boolean excludeSender) {
            this.room = room;
            this.message = message;
            this.senderId = senderId;
            this.excludeSender = excludeSender;
        }
    }
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║   Chatroom Server - Named Pipe Version        ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println("Broadcaster threads: " + NUM_BROADCASTERS);
        
        new File(PIPE_DIR).mkdirs();
    
        try {
            new File(CONTROL_PIPE).createNewFile();
        } catch (IOException e) {
            System.err.println("Failed to create control pipe: " + e.getMessage());
            return;
        }
        
        Thread routerThread = new Thread(new Router());
        routerThread.start();
        ExecutorService broadcasterPool = Executors.newFixedThreadPool(NUM_BROADCASTERS);
        for (int i = 0; i < NUM_BROADCASTERS; i++) {
            broadcasterPool.execute(new Broadcaster(i));
        }
        
        Thread heartbeatMonitor = new Thread(new HeartbeatMonitor());
        heartbeatMonitor.setDaemon(true);
        heartbeatMonitor.start();
        
        System.out.println("Server ready. Waiting for clients...");
        System.out.println("Control pipe: " + CONTROL_PIPE);
        System.out.println("Reply pipes: " + PIPE_DIR + "<clientId>.txt\n");
        
        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n=== Server Shutting Down ===");
            running = false;
            broadcasterPool.shutdown();
            cleanup();
        }));
        
        try {
            routerThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    // Router Thread - อ่านคำสั่งจาก control pipe
    static class Router implements Runnable {
        @Override
        public void run() {
            System.out.println("[Router] Started - listening on " + CONTROL_PIPE);
            
            while (running) {
                try {
                    // อ่านจาก control pipe
                    BufferedReader reader = new BufferedReader(new FileReader(CONTROL_PIPE));
                    String line;
                    
                    while ((line = reader.readLine()) != null && running) {
                        if (line.trim().isEmpty()) continue;
                        
                        processCommand(line);
                    }
                    
                    reader.close();
                    
                    // Clear pipe content
                    new PrintWriter(CONTROL_PIPE).close();
                    
                    Thread.sleep(100); // Polling interval
                    
                } catch (IOException | InterruptedException e) {
                    if (running) {
                        System.err.println("[Router] Error: " + e.getMessage());
                    }
                }
            }
            
            System.out.println("[Router] Stopped");
        }
        
        private void processCommand(String line) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) return;
            
            String clientId = parts[0];
            String command = parts[1].toUpperCase();
            String[] args = parts.length > 2 ? parts[2].split("\\|") : new String[0];
            
            Message msg = new Message(clientId, command, args);
            
            switch (command) {
                case "LOGIN":
                    handleLogin(msg);
                    break;
                case "HEARTBEAT":
                    handleHeartbeat(msg);
                    break;
                case "JOIN":
                    handleJoin(msg);
                    break;
                case "SAY":
                    handleSay(msg);
                    break;
                case "DM":
                    handleDM(msg);
                    break;
                case "WHO":
                    handleWho(msg);
                    break;
                case "LEAVE":
                    handleLeave(msg);
                    break;
                case "QUIT":
                    handleQuit(msg);
                    break;
                default:
                    sendToClient(msg.clientId, "ERROR: Unknown command: " + command);
            }
        }
        
        private void handleLogin(Message msg) {
            if (msg.args.length < 1) {
                sendToClient(msg.clientId, "ERROR: LOGIN requires username");
                return;
            }
            
            String username = msg.args[0];
            String replyPipe = PIPE_DIR + msg.clientId + ".txt";
            
            rwLock.writeLock().lock();
            try {
                // Create reply pipe
                try {
                    new File(replyPipe).createNewFile();
                } catch (IOException e) {
                    sendToClient(msg.clientId, "ERROR: Failed to create reply pipe");
                    return;
                }
                
                ClientInfo info = new ClientInfo(msg.clientId, username, replyPipe);
                clientRegistry.put(msg.clientId, info);
                lastHeartbeat.put(msg.clientId, System.currentTimeMillis());
                
                System.out.println("[Router] ✓ " + username + " (" + msg.clientId + ") logged in");
                sendToClient(msg.clientId, "SYSTEM: Welcome, " + username + "!");
                sendToClient(msg.clientId, "SYSTEM: You are now connected as " + msg.clientId);
                
            } finally {
                rwLock.writeLock().unlock();
            }
        }
        
        private void handleHeartbeat(Message msg) {
            lastHeartbeat.put(msg.clientId, System.currentTimeMillis());
        }
        
        private void handleJoin(Message msg) {
            if (msg.args.length < 1) {
                sendToClient(msg.clientId, "ERROR: JOIN requires room name");
                return;
            }
            
            String room = msg.args[0];
            
            rwLock.writeLock().lock();
            try {
                ClientInfo info = clientRegistry.get(msg.clientId);
                if (info == null) {
                    sendToClient(msg.clientId, "ERROR: Please LOGIN first");
                    return;
                }
                
                roomRegistry.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet())
                           .add(msg.clientId);
                System.out.println("[Router] " + info.username + " joined " + room);
                sendToClient(msg.clientId, "SYSTEM: You joined " + room);
                // Broadcast join event
                BroadcastTask task = new BroadcastTask(room, 
                    "SYSTEM: " + info.username + " joined the room", msg.clientId, true);
                broadcastQueue.offer(task);
                
            } finally {
                rwLock.writeLock().unlock();
            }
        }
        
        private void handleSay(Message msg) {
            if (msg.args.length < 2) {
                sendToClient(msg.clientId, "ERROR: SAY requires room and message");
                return;
            }
            
            String room = msg.args[0];
            String message = String.join(" ", Arrays.copyOfRange(msg.args, 1, msg.args.length));
            
            rwLock.readLock().lock();
            try {
                ClientInfo info = clientRegistry.get(msg.clientId);
                if (info == null) {
                    sendToClient(msg.clientId, "ERROR: Please LOGIN first");
                    return;
                }
                
                Set<String> members = roomRegistry.get(room);
                if (members == null || !members.contains(msg.clientId)) {
                    sendToClient(msg.clientId, "ERROR: You are not in room " + room);
                    return;
                }
                
                System.out.println("[Router] [" + room + "] " + info.username + ": " + message);
                
                // Create broadcast task (ส่งกลับ sender ด้วย)
                BroadcastTask task = new BroadcastTask(room, 
                    "[" + room + "] " + info.username + ": " + message, msg.clientId, false);
                broadcastQueue.offer(task);
                
            } finally {
                rwLock.readLock().unlock();
            }
        }
        
        private void handleDM(Message msg) {
            if (msg.args.length < 2) {
                sendToClient(msg.clientId, "ERROR: DM requires recipient and message");
                return;
            }
            
            String recipientName = msg.args[0];
            String message = String.join(" ", Arrays.copyOfRange(msg.args, 1, msg.args.length));
            
            rwLock.readLock().lock();
            try {
                ClientInfo sender = clientRegistry.get(msg.clientId);
                if (sender == null) {
                    sendToClient(msg.clientId, "ERROR: Please LOGIN first");
                    return;
                }
                
                // Find recipient by username
                ClientInfo recipient = null;
                for (ClientInfo info : clientRegistry.values()) {
                    if (info.username.equals(recipientName)) {
                        recipient = info;
                        break;
                    }
                }
                
                if (recipient == null) {
                    sendToClient(msg.clientId, "ERROR: User " + recipientName + " not found");
                    return;
                }
                
                System.out.println("[Router] DM: " + sender.username + " -> " + recipient.username);
                
                sendToClient(recipient.clientId, "[DM from " + sender.username + "] " + message);
                sendToClient(msg.clientId, "[DM to " + recipient.username + "] " + message);
                
            } finally {
                rwLock.readLock().unlock();
            }
        }
        
        private void handleWho(Message msg) {
            if (msg.args.length < 1) {
                sendToClient(msg.clientId, "ERROR: WHO requires room name");
                return;
            }
            
            String room = msg.args[0];
            
            rwLock.readLock().lock();
            try {
                Set<String> members = roomRegistry.get(room);
                if (members == null || members.isEmpty()) {
                    sendToClient(msg.clientId, "SYSTEM: Room " + room + " is empty");
                } else {
                    List<String> usernames = new ArrayList<>();
                    for (String clientId : members) {
                        ClientInfo info = clientRegistry.get(clientId);
                        if (info != null) {
                            usernames.add(info.username);
                        }
                    }
                    String memberList = String.join(", ", usernames);
                    sendToClient(msg.clientId, "SYSTEM: Members in " + room + ": " + memberList);
                }
            } finally {
                rwLock.readLock().unlock();
            }
        }
        
        private void handleLeave(Message msg) {
            if (msg.args.length < 1) {
                sendToClient(msg.clientId, "ERROR: LEAVE requires room name");
                return;
            }
            
            String room = msg.args[0];
            
            rwLock.writeLock().lock();
            try {
                ClientInfo info = clientRegistry.get(msg.clientId);
                if (info == null) return;
                
                Set<String> members = roomRegistry.get(room);
                if (members != null) {
                    members.remove(msg.clientId);
                    
                    System.out.println("[Router] " + info.username + " left " + room);
                    sendToClient(msg.clientId, "SYSTEM: You left " + room);
                    
                    // Broadcast leave event
                    if (!members.isEmpty()) {
                        BroadcastTask task = new BroadcastTask(room, 
                            "SYSTEM: " + info.username + " left the room", msg.clientId, true);
                        broadcastQueue.offer(task);
                    } else {
                        System.out.println("[Router] Room " + room + " is now empty");
                    }
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }
        
        private void handleQuit(Message msg) {
            rwLock.writeLock().lock();
            try {
                ClientInfo info = clientRegistry.get(msg.clientId);
                if (info == null) return;
                
                // Remove from all rooms
                for (Map.Entry<String, Set<String>> entry : roomRegistry.entrySet()) {
                    Set<String> members = entry.getValue();
                    if (members.remove(msg.clientId) && !members.isEmpty()) {
                        // Notify room members
                        BroadcastTask task = new BroadcastTask(entry.getKey(), 
                            "SYSTEM: " + info.username + " left the room", msg.clientId, true);
                        broadcastQueue.offer(task);
                    }
                }
                
                clientRegistry.remove(msg.clientId);
                lastHeartbeat.remove(msg.clientId);
                
                System.out.println("[Router] ✗ " + info.username + " disconnected");
                sendToClient(msg.clientId, "SYSTEM: Goodbye!");
                
                // Delete reply pipe
                new File(info.replyPipe).delete();
                
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }
    
    // Broadcaster Thread
    static class Broadcaster implements Runnable {
        private final int id;
        
        Broadcaster(int id) {
            this.id = id;
        }
        
        @Override
        public void run() {
            System.out.println("[Broadcaster-" + id + "] Started");
            
            while (running) {
                try {
                    BroadcastTask task = broadcastQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task == null) continue;
                    
                    broadcast(task);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            System.out.println("[Broadcaster-" + id + "] Stopped");
        }
        
        private void broadcast(BroadcastTask task) {
            rwLock.readLock().lock();
            try {
                Set<String> members = roomRegistry.get(task.room);
                if (members == null) return;
                
                for (String clientId : members) {
                    if (task.excludeSender && clientId.equals(task.senderId)) {
                        continue;
                    }
                    
                    sendToClient(clientId, task.message);
                }
                
            } finally {
                rwLock.readLock().unlock();
            }
        }
    }
    
    // Heartbeat Monitor
    static class HeartbeatMonitor implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(5000); // Check every 5 seconds
                    
                    long now = System.currentTimeMillis();
                    List<String> deadClients = new ArrayList<>();
                    
                    for (Map.Entry<String, Long> entry : lastHeartbeat.entrySet()) {
                        if (now - entry.getValue() > HEARTBEAT_TIMEOUT) {
                            deadClients.add(entry.getKey());
                        }
                    }
                    
                    // Remove dead clients
                    for (String clientId : deadClients) {
                        System.out.println("[Heartbeat] Client " + clientId + " timeout - disconnecting");
                        Message msg = new Message(clientId, "QUIT", new String[0]);
                        // Handle as QUIT
                        rwLock.writeLock().lock();
                        try {
                            ClientInfo info = clientRegistry.get(clientId);
                            if (info != null) {
                                for (Set<String> members : roomRegistry.values()) {
                                    members.remove(clientId);
                                }
                                clientRegistry.remove(clientId);
                                lastHeartbeat.remove(clientId);
                                new File(info.replyPipe).delete();
                            }
                        } finally {
                            rwLock.writeLock().unlock();
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
    
    // Helper - ส่งข้อความไปยัง client
    private static void sendToClient(String clientId, String message) {
        ClientInfo info = clientRegistry.get(clientId);
        if (info == null) return;
        
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(info.replyPipe, true));
            writer.println(message);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            System.err.println("[Server] Failed to send to " + clientId + ": " + e.getMessage());
        }
    }
    
    private static void cleanup() {
        try {
            new File(CONTROL_PIPE).delete();
            File pipeDir = new File(PIPE_DIR);
            File[] pipes = pipeDir.listFiles();
            if (pipes != null) {
                for (File pipe : pipes) {
                    pipe.delete();
                }
            }
            pipeDir.delete();
        } catch (Exception e) {
            System.err.println("Cleanup error: " + e.getMessage());
        }
    }
}