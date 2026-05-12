import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * ATM服务器端
 * 修复问题：多线程取款竞态条件、服务器日志缺失客户端标识
 * 优化内容：显式状态机、服务器优雅关闭、文件路径规范化
 * 新增功能：交易记录持久化（transactions.txt）
 * 用法: java ATMServer [port]
 * 默认端口: 2525
 */
public class ATMServer {
    private static final int DEFAULT_PORT = 2525;
    // 数据存储（线程安全的ConcurrentHashMap）
    private static final Map<String, String> userPasswords = new ConcurrentHashMap<>();
    private static final Map<String, Double> userBalances = new ConcurrentHashMap<>();
    // 日期格式化（线程安全，每次使用新建实例）
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // 会话状态枚举（替代原布尔值，显式管理状态流转）
    private enum SessionState {
        INIT,           // 初始状态，等待HELO命令
        AUTH_REQUIRED,  // 已收到HELO，等待PASS命令
        LOGGED_IN       // 认证成功，可执行业务操作
    }

    public static void main(String[] args) {
        // 解析端口号
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1024 || port > 65535) {
                    System.err.println("[警告] 端口号范围应为1024~65535，使用默认端口 " + DEFAULT_PORT);
                    port = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("[警告] 无效端口号，使用默认端口 " + DEFAULT_PORT);
            }
        }

        // 加载初始数据
        loadUserData();
        System.out.println("[信息] 用户数据加载完成，共加载 " + userPasswords.size() + " 个账户");

        // 注册关闭钩子：服务器退出时自动保存余额数据
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[信息] 服务器正在关闭，保存余额数据...");
            saveBalances();
            System.out.println("[信息] 数据保存完成，服务器已正常关闭");
        }));

        // 启动服务器
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[信息] ATM服务器已启动，监听端口 " + port);
            System.out.println("[信息] 按 Ctrl+C 停止服务器");
            
            // 使用缓存线程池处理并发连接
            ExecutorService threadPool = Executors.newCachedThreadPool();
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("[错误] 服务器启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 从users.txt和balances.txt加载用户数据
     * 文件需放置在程序运行的根目录
     */
    private static void loadUserData() {
        // 加载用户口令
        File usersFile = new File("users.txt");
        if (usersFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(usersFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 2) {
                        userPasswords.put(parts[0], parts[1]);
                    }
                }
            } catch (IOException e) {
                System.err.println("[警告] 读取users.txt失败: " + e.getMessage());
            }
        } else {
            System.err.println("[警告] users.txt不存在，将使用空用户数据集");
        }

        // 加载账户余额
        File balancesFile = new File("balances.txt");
        if (balancesFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(balancesFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 2) {
                        try {
                            userBalances.put(parts[0], Double.parseDouble(parts[1]));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (IOException e) {
                System.err.println("[警告] 读取balances.txt失败: " + e.getMessage());
            }
        } else {
            System.err.println("[警告] balances.txt不存在，将使用空余额数据集");
        }
    }

    /**
     * 保存所有账户余额到balances.txt（同步方法，避免并发写冲突）
     */
    private static synchronized void saveBalances() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("balances.txt"))) {
            for (Map.Entry<String, Double> entry : userBalances.entrySet()) {
                pw.printf("%s %.2f%n", entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("[错误] 保存balances.txt失败: " + e.getMessage());
        }
    }

    /**
     * 保存交易记录到transactions.txt（同步方法，避免并发写冲突）
     * @param clientAddr 客户端IP:端口
     * @param cardNo 卡号（未登录为null）
     * @param operation 操作类型（HELO/PASS/BALA/WDRA/QUIT）
     * @param amount 操作金额（无金额为0.0）
     * @param result 操作结果（成功/失败）
     */
    private static synchronized void saveTransaction(String clientAddr, String cardNo, 
                                                    String operation, double amount, String result) {
        String timestamp = new SimpleDateFormat(DATE_FORMAT).format(new Date());
        String card = (cardNo == null) ? "未登录" : cardNo;
        String amtStr = (amount == 0.0) ? "-" : String.format("%.2f", amount);
        
        String record = String.format("%s | %-15s | %-8s | %-6s | %-8s | %s%n",
                timestamp, clientAddr, card, operation, amtStr, result);
        
        try (FileWriter fw = new FileWriter("transactions.txt", true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(record);
        } catch (IOException e) {
            System.err.println("[错误] 保存交易记录失败: " + e.getMessage());
        }
    }

    /**
     * 处理单个客户端连接的线程类
     * 每个客户端连接对应一个独立的Handler实例
     */
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientAddr; // 客户端标识：IP:端口
        private String currentCard = null;
        private SessionState state = SessionState.INIT; // 会话状态

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientAddr = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        @Override
        public void run() {
            System.out.println("[连接] 新客户端接入: " + clientAddr);
            saveTransaction(clientAddr, null, "CONNECT", 0.0, "成功");

            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String line;
                while ((line = in.readLine()) != null) {
                    String cmd = line.trim();
                    System.out.printf("[收到] %s: %s%n", clientAddr, cmd);
                    
                    String response = processCommand(cmd);
                    out.println(response);
                    System.out.printf("[回复] %s: %s%n", clientAddr, response);

                    // 收到QUIT命令，结束会话
                    if (response.equals("BYE")) {
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.printf("[异常] 客户端%s连接异常: %s%n", clientAddr, e.getMessage());
                saveTransaction(clientAddr, currentCard, "DISCONNECT", 0.0, "异常断开");
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                System.out.println("[断开] 客户端连接关闭: " + clientAddr);
            }
        }

        /**
         * 处理客户端命令，严格遵循状态机规则
         * @param cmd 客户端发送的原始命令
         * @return 服务器响应报文
         */
        private String processCommand(String cmd) {
            if (cmd.isEmpty()) {
                saveTransaction(clientAddr, currentCard, "EMPTY", 0.0, "失败");
                return "401 ERROR!";
            }

            String[] parts = cmd.split("\\s+", 2);
            String command = parts[0].toUpperCase();

            // 状态机校验：不同状态允许执行的命令不同
            switch (state) {
                case INIT:
                    if (!command.equals("HELO")) {
                        saveTransaction(clientAddr, null, command, 0.0, "失败(非法状态)");
                        return "401 ERROR!";
                    }
                    break;
                case AUTH_REQUIRED:
                    if (!command.equals("PASS")) {
                        saveTransaction(clientAddr, currentCard, command, 0.0, "失败(未认证)");
                        return "401 ERROR!";
                    }
                    break;
                case LOGGED_IN:
                    if (!Arrays.asList("BALA", "WDRA", "QUIT").contains(command)) {
                        saveTransaction(clientAddr, currentCard, command, 0.0, "失败(非法命令)");
                        return "401 ERROR!";
                    }
                    break;
            }

            // 命令处理逻辑
            switch (command) {
                case "HELO":
                    if (parts.length < 2) {
                        saveTransaction(clientAddr, null, "HELO", 0.0, "失败(缺少卡号)");
                        return "401 ERROR!";
                    }
                    currentCard = parts[1];
                    if (userPasswords.containsKey(currentCard)) {
                        state = SessionState.AUTH_REQUIRED;
                        saveTransaction(clientAddr, currentCard, "HELO", 0.0, "成功");
                        return "500 AUTH REQUIRE";
                    } else {
                        currentCard = null;
                        saveTransaction(clientAddr, null, "HELO", 0.0, "失败(卡号不存在)");
                        return "401 ERROR!";
                    }

                case "PASS":
                    if (parts.length < 2) {
                        saveTransaction(clientAddr, currentCard, "PASS", 0.0, "失败(缺少口令)");
                        return "401 ERROR!";
                    }
                    String inputPwd = parts[1];
                    String correctPwd = userPasswords.get(currentCard);
                    if (correctPwd != null && correctPwd.equals(inputPwd)) {
                        state = SessionState.LOGGED_IN;
                        saveTransaction(clientAddr, currentCard, "PASS", 0.0, "成功");
                        return "525 OK!";
                    } else {
                        saveTransaction(clientAddr, currentCard, "PASS", 0.0, "失败(口令错误)");
                        return "401 ERROR!";
                    }

                case "BALA":
                    Double balance = userBalances.get(currentCard);
                    if (balance == null) {
                        saveTransaction(clientAddr, currentCard, "BALA", 0.0, "失败(账户不存在)");
                        return "401 ERROR!";
                    }
                    saveTransaction(clientAddr, currentCard, "BALA", 0.0, "成功");
                    return String.format("AMNT:%.2f", balance);

                case "WDRA":
                    if (parts.length < 2) {
                        saveTransaction(clientAddr, currentCard, "WDRA", 0.0, "失败(缺少金额)");
                        return "401 ERROR!";
                    }
                    double amount;
                    try {
                        amount = Double.parseDouble(parts[1]);
                        if (amount <= 0) {
                            throw new NumberFormatException("金额必须大于0");
                        }
                    } catch (NumberFormatException e) {
                        saveTransaction(clientAddr, currentCard, "WDRA", 0.0, "失败(金额格式错误)");
                        return "401 ERROR!";
                    }

                    // 对当前账户加锁，解决多线程取款竞态条件
                    synchronized (currentCard.intern()) {
                        Double currentBalance = userBalances.get(currentCard);
                        if (currentBalance == null) {
                            saveTransaction(clientAddr, currentCard, "WDRA", amount, "失败(账户不存在)");
                            return "401 ERROR!";
                        }
                        if (currentBalance >= amount) {
                            userBalances.put(currentCard, currentBalance - amount);
                            saveBalances(); // 立即持久化
                            saveTransaction(clientAddr, currentCard, "WDRA", amount, "成功");
                            return "525 OK!";
                        } else {
                            saveTransaction(clientAddr, currentCard, "WDRA", amount, "失败(余额不足)");
                            return "401 ERROR!";
                        }
                    }

                case "QUIT":
                    saveTransaction(clientAddr, currentCard, "QUIT", 0.0, "成功");
                    state = SessionState.INIT;
                    currentCard = null;
                    return "BYE";

                default:
                    saveTransaction(clientAddr, currentCard, command, 0.0, "失败(未知命令)");
                    return "401 ERROR!";
            }
        }
    }
}