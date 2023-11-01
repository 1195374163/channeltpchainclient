import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import network.RequestEncoder;
import network.RequestMessage;
import network.ResponseDecoder;
import network.ResponseMessage;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.Status;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChainClient extends DB {
    //以下都是类变量多个对象共享
    
    private static AtomicInteger idCounter;
    private static int timeoutMillis;
    private static byte readType;
    private static List<Channel> servers;
    
    private static final AtomicInteger initCounter = new AtomicInteger();
    // 对服务器的偏重
    private static String[] weights;
    private static double totalWeight;
    
    //这是这个类变量，类的所有实例共享的 存放的是异步操作的中转,发出操作等待结果
    private static final Map<Channel, Map<Integer, CompletableFuture<ResponseMessage>>> opCallbacks = new HashMap<>();
    
    
    // 线程的本地变量，当前线程连接的服务端，默认是均匀分布
    private static final ThreadLocal<Channel> threadServer = new ThreadLocal<>();
    
    
    
    
    
    
    
    
    //这个init()方法主要是设置线程使用与测试数据库的哪个连接通道：将结果保存在threadServer中
    @Override
    public void init() {
      try {
        //System.err.println(i1 + " " + Thread.currentThread().toString());
          //server是channel
        synchronized (opCallbacks) {
            //ONCE,只执行一次
          if (servers == null) {
            //设定等待服务器的超时时间，
            timeoutMillis = Integer.parseInt(getProperties().getProperty("timeout_millis"));
            int serverPort = Integer.parseInt(getProperties().getProperty("app_server_port"));
            int nFrontends = Integer.parseInt(getProperties().getProperty("n_frontends"));
            String readProp = getProperties().getProperty("read_type", "strong");
            if (readProp.equals("weak")) readType = RequestMessage.WEAK_READ;
            else if (readProp.equals("strong")) readType = RequestMessage.STRONG_READ;
            idCounter = new AtomicInteger(0);
            //System.err.println("My id: " + myNumber + " field length: " + getProperties().getProperty("fieldlength") +
            //    " client id: " + idCounter.get());
              
            servers = new LinkedList<>();

            String host = getProperties().getProperty("hosts");
            String[] hosts = host.split(",");
            
            
            //对服务器的侧重
            totalWeight = 0;
            if (getProperties().containsKey("weights") && !getProperties().getProperty("weights").isEmpty()) {
              weights = getProperties().getProperty("weights").split(":");

              if (weights.length != hosts.length * nFrontends) {
                System.err.println("Weight does not match hosts");
                System.exit(-1);
              }

              for (String weight : weights) totalWeight += Double.parseDouble(weight);
            }
            
            
            //连接服务端的net框架
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            // 生成并设置Netty的客户端
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.handler(new ChannelInitializer<SocketChannel>() {
              @Override
              public void initChannel(SocketChannel ch) {
                //System.err.println("InitChannel: " + ch);
                ch.pipeline().addLast(new RequestEncoder(), new ResponseDecoder(), new ClientHandler());
              }
            });
            
            // 将服务器的异步返回结果连接放入一个列表
            List<ChannelFuture> connectFutures = new LinkedList<>();
            
            // 连接提供的每一台服务器
            for (String s : hosts) {//对于系统中每台host
              for (int f = 0; f < nFrontends; f++) {
                InetAddress addr = InetAddress.getByName(s);
                int port = serverPort + f;
                //System.err.println("Connecting to " + addr + ":" + port);
                ChannelFuture connect = b.connect(addr, port);
                connectFutures.add(connect);
                //这个放的是channel实例
                servers.add(connect.channel());
                opCallbacks.put(connect.channel(), new ConcurrentHashMap<>());
              }
            }
            for (ChannelFuture f : connectFutures) {
              f.sync();
            }
            System.err.println("Connected to all servers!");
            //END ONCE ----------
          }

          
          int randIdx = -1;
          if (totalWeight == 0) {//均匀分部到服务器连接
            int threadId = initCounter.getAndIncrement();
            randIdx = threadId % servers.size();
          } else {
            double random = Math.random() * totalWeight;
            for (int i = 0; i < servers.size(); ++i) {
              random -= Double.parseDouble(weights[i]);
              if (random <= 0.0d) {
                randIdx = i;
                break;
              }
            }
          }
          // 本线程的连接数据库的通道
          threadServer.set(servers.get(randIdx));
        }
      } catch (UnknownHostException | InterruptedException e) {
        e.printStackTrace();
        System.exit(1);
      }
    }

    
    
    
    
    
    @Override
    public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
      try {
        int id = idCounter.incrementAndGet();
        RequestMessage requestMessage = new RequestMessage(id, readType, key, new byte[0]);
        return executeOperation(requestMessage);
      } catch (Exception e) {
        e.printStackTrace();
        System.exit(1);
        return Status.ERROR;
      }
    }

    @Override
    public Status update(String table, String key, Map<String, ByteIterator> values) {
      return insert(table, key, values);
    }

    @Override
    public Status insert(String table, String key, Map<String, ByteIterator> values) {
      try {
        byte[] value = values.values().iterator().next().toArray();
        int id = idCounter.incrementAndGet();
        RequestMessage requestMessage = new RequestMessage(id, RequestMessage.WRITE, key, value);
        return executeOperation(requestMessage);
      } catch (Exception e) {
        e.printStackTrace();
        System.exit(1);
        return Status.ERROR;
      }
    }
    
    //对连接的数据库具体执行相应的措施,不管是插入还是读取
    private Status executeOperation(RequestMessage requestMessage) throws InterruptedException, ExecutionException {
      CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
      Channel channel = threadServer.get();
      opCallbacks.get(channel).put(requestMessage.getcId(), future);
      channel.writeAndFlush(requestMessage);
      //在指定的时间内(设置为50秒)收不到回复的消息，系统以异常结束
      try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        return Status.OK;
      } catch (TimeoutException ex) {
        System.err.println("Op Timed out..." + channel.remoteAddress() + " " + requestMessage.getcId());
        System.exit(1);
        return Status.SERVICE_UNAVAILABLE;
      }
    }

    @Override
    public Status scan(String t, String sK, int rC, Set<String> f, Vector<HashMap<String, ByteIterator>> res) {
      throw new AssertionError();
    }

    @Override
    public Status delete(String table, String key) {
      throw new AssertionError();
    }
    
    //Netty框架的客户端设置
    static class ClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
          System.err.println("Unexpected event, exiting: " + evt);
          System.exit(1);
          ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
          System.err.println("Server connection lost, exiting: " + ctx.channel().remoteAddress());
          //System.exit(1);
          ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          System.err.println("Exception caught, exiting: " + cause);
          cause.printStackTrace();
          System.exit(1);
          ctx.fireExceptionCaught(cause);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
          //System.err.println("Connected to " + ctx.channel());
        }
        
        
        //得到数据库的返回结果,并对future手动进行进行complete
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
          //System.err.println("Message received: " + msg);
          ResponseMessage resp = (ResponseMessage) msg;
          opCallbacks.get(ctx.channel()).get(resp.getcId()).complete(resp);
        }
    }
}
