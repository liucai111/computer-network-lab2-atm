import java.io.*;
import java.net.*;
import java.util.Arrays;

/**
 * ATM客户端（最终完美版）
 * 修复问题：口令明文显示、空命令/非法输入未校验、缺失Arrays导入、变量名笔误
 * 优化内容：命令格式提前校验、错误提示更友好
 * 用法: java ATMClient [serverIP] [port]
 * 默认: 127.0.0.1 2525
 */
public class ATMClient {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 2525;

    public static void main(String[] args) {
        // 解析服务器地址和端口
        String serverHost = DEFAULT_HOST;
        int serverPort = DEFAULT_PORT;

        if (args.length >= 1) {
            serverHost = args[0];
        }
        if (args.length >= 2) {
            try {
                serverPort = Integer.parseInt(args[1]);
                // 修复：port → serverPort
                if (serverPort < 1024 || serverPort > 65535) {
                    System.err.println("[警告] 端口号范围应为1024~65535，使用默认端口 " + DEFAULT_PORT);
                    serverPort = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("[警告] 无效端口号，使用默认端口 " + DEFAULT_PORT);
            }
        }

        // 连接服务器
        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {

            System.out.println("[成功] 已连接到ATM服务器 " + serverHost + ":" + serverPort);
            System.out.println("----------------------------------------");

            // 1. 输入卡号
            System.out.print("请输入卡号: ");
            String cardNo = consoleReader.readLine().trim();
            out.println("HELO " + cardNo);

            String response = in.readLine();
            if (!response.startsWith("500")) {
                System.out.println("[错误] 服务器拒绝连接: " + response);
                return;
            }
            System.out.println("[提示] 服务器已验证卡号，要求输入口令");

            // 2. 输入口令（隐藏输入）
            String passwd;
            Console console = System.console();
            if (console != null) {
                // 控制台环境下隐藏输入
                char[] passwdChars = console.readPassword("请输入口令: ");
                passwd = new String(passwdChars);
                // 清空密码数组，避免内存泄露
                Arrays.fill(passwdChars, ' ');
            } else {
                // IDE等无Console环境下使用普通输入（会明文显示）
                System.out.print("请输入口令(当前环境不支持隐藏输入): ");
                passwd = consoleReader.readLine().trim();
            }
            out.println("PASS " + passwd);

            response = in.readLine();
            if (!"525 OK!".equals(response)) {
                System.out.println("[错误] 认证失败: 卡号或口令错误");
                return;
            }
            System.out.println("[成功] 认证通过，欢迎使用ATM系统");
            System.out.println("----------------------------------------");

            // 3. 主菜单循环
            while (true) {
                System.out.println("\n可选操作:");
                System.out.println("  BALA        - 查询余额");
                System.out.println("  WDRA <金额> - 取款（例如: WDRA 100）");
                System.out.println("  QUIT        - 退出系统");
                System.out.print("> ");

                String input = consoleReader.readLine();
                if (input == null) break;
                input = input.trim();

                // 客户端提前校验空命令
                if (input.isEmpty()) {
                    System.out.println("[提示] 请输入有效命令");
                    continue;
                }

                // 客户端提前校验WDRA命令格式
                String[] parts = input.split("\\s+");
                if (parts[0].equalsIgnoreCase("WDRA")) {
                    if (parts.length != 2) {
                        System.out.println("[错误] 格式错误！正确格式: WDRA <金额>");
                        continue;
                    }
                    try {
                        double amt = Double.parseDouble(parts[1]);
                        if (amt <= 0) {
                            System.out.println("[错误] 取款金额必须大于0");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("[错误] 金额必须是有效的数字");
                        continue;
                    }
                }

                // 发送命令到服务器
                out.println(input);
                String resp = in.readLine();
                if (resp == null) {
                    System.out.println("[错误] 服务器连接中断");
                    break;
                }

                // 处理服务器响应
                if (resp.startsWith("AMNT:")) {
                    System.out.printf("[结果] 当前账户余额: %.2f 元%n", Double.parseDouble(resp.substring(5)));
                } else if ("525 OK!".equals(resp)) {
                    System.out.println("[结果] 操作成功！");
                } else if ("401 ERROR!".equals(resp)) {
                    System.out.println("[结果] 操作失败！请检查余额是否充足");
                } else if ("BYE".equals(resp)) {
                    System.out.println("[提示] 服务器已结束会话，感谢使用！");
                    break;
                } else {
                    System.out.println("[错误] 未知服务器响应: " + resp);
                }

                // 主动退出
                if ("QUIT".equalsIgnoreCase(input)) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[错误] 客户端异常: " + e.getMessage());
        }
    }
}