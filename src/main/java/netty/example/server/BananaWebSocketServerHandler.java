package netty.example.server;

import com.google.common.base.Strings;
import com.google.gson.JsonSyntaxException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import netty.example.serviceimpl.BananaService;
import netty.example.util.CODE;
import netty.example.util.Request;
import netty.example.util.Response;


/**
 * WebSocket服务端Handler
 *
 */
public class BananaWebSocketServerHandler extends SimpleChannelInboundHandler<Object> {
	//private static final Logger LOG = Logger.getLogger(BananaWebSocketServerHandler.class.getName());
	
	private WebSocketServerHandshaker handshaker;
	private ChannelHandlerContext ctx;
	private String sessionId;

	@Override
	public void messageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
		System.out.println("messageReceived:1");
		if (msg instanceof FullHttpRequest) { // 传统的HTTP接入
			handleHttpRequest(ctx, (FullHttpRequest) msg);
		} else if (msg instanceof WebSocketFrame) { // WebSocket接入
			handleWebSocketFrame(ctx, (WebSocketFrame) msg);
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		System.out.println("channelReadComplete:2");
		ctx.flush();
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		System.out.println("exceptionCaught:3");
		//LOG.error("WebSocket异常", cause);
		ctx.close();
		//LOG.info(sessionId + " 	注销");
		BananaService.logout(sessionId); // 注销
		BananaService.notifyDownline(sessionId); // 通知有人下线
	}

	@Override
	public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
		System.out.println("close:4");
		//LOG.info("WebSocket关闭");
		super.close(ctx, promise);
		//LOG.info(sessionId + " 注销");
		BananaService.logout(sessionId); // 注销
		BananaService.notifyDownline(sessionId); // 通知有人下线
	}

	/**
	 * 处理Http请求，完成WebSocket握手<br/>
	 * 注意：WebSocket连接第一次请求使用的是Http
	 * @param ctx
	 * @param request
	 * @throws Exception
	 */
	private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		System.out.println("handleHttpRequest:5");
		// 如果HTTP解码失败，返回HHTP异常
		if (!request.getDecoderResult().isSuccess() || (!"websocket".equals(request.headers().get("Upgrade")))) {
			sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
			return;
		}
		System.out.println("request.headers().get(\"Upgrade\") : " + request.headers().get("Upgrade"));
		System.out.println("request.getDecoderResult() : " + request.getDecoderResult());

		// 正常WebSocket的Http连接请求，构造握手响应返回
		WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory("ws://" + request.headers().get(HttpHeaders.Names.HOST), null, false);
		System.out.println("request.headers() : " + request.headers());
		System.out.println("request.headers().get(HttpHeaders.Names.HOST) : " + request.headers().get(HttpHeaders.Names.HOST));
		handshaker = wsFactory.newHandshaker(request);
		System.out.println("handshaker : " + handshaker);
		if (handshaker == null) { // 无法处理的websocket版本
			WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel());
		} else { // 向客户端发送websocket握手,完成握手
			handshaker.handshake(ctx.channel(), request);
			System.out.println("handshaker : " + handshaker);
			System.out.println("ctx.channel() : " + ctx.channel());
			// 记录管道处理上下文，便于服务器推送数据到客户端
			System.out.println("ctx : " + ctx);
			this.ctx = ctx;
		}
	}

	/**
	 * 处理Socket请求
	 * @param ctx
	 * @param frame
	 * @throws Exception 
	 */
	private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
		System.out.println("handleWebSocketFrame:6");
		// 判断是否是关闭链路的指令
		if (frame instanceof CloseWebSocketFrame) {
			handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
			return;
		}
		// 判断是否是Ping消息
		if (frame instanceof PingWebSocketFrame) {
			ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
			return;
		}
		// 当前只支持文本消息，不支持二进制消息
		if (!(frame instanceof TextWebSocketFrame)) {
			throw new UnsupportedOperationException("当前只支持文本消息，不支持二进制消息");
		}
		
		// 处理来自客户端的WebSocket请求
		try {
			Request request = Request.create(((TextWebSocketFrame)frame).text());
			System.out.println("(TextWebSocketFrame)frame : " + frame);
			System.out.println("((TextWebSocketFrame)frame).text() : " + ((TextWebSocketFrame)frame).text());
			System.out.println("request : " + request);
			Response response = new Response();
			final Response response1 = response.setServiceId(request.getServiceId());
			if (CODE.online.code.intValue() == request.getServiceId()) { // 客户端注册
				String requestId = request.getRequestId();
				if (Strings.isNullOrEmpty(requestId)) {
					response.setIsSucc(false).setMessage("requestId不能为空");
					return;
				} else if (Strings.isNullOrEmpty(request.getName())) {
					response.setIsSucc(false).setMessage("name不能为空");
					return;
				} else if (BananaService.bananaWatchMap.containsKey(requestId)) {
					response.setIsSucc(false).setMessage("您已经注册了，不能重复注册");
					return;
				}
				if (!BananaService.register(requestId, new BananaService(ctx, request.getName()))) {
					response.setIsSucc(false).setMessage("注册失败");
				} else {
					response.setIsSucc(true).setMessage("注册成功");
					
					BananaService.bananaWatchMap.forEach((reqId, callBack) -> {
						response.getHadOnline().put(reqId, ((BananaService)callBack).getName()); // 将已经上线的人员返回
						
						if (!reqId.equals(requestId)) {
							Request serviceRequest = new Request();
							serviceRequest.setServiceId(CODE.online.code);
							serviceRequest.setRequestId(requestId);
							serviceRequest.setName(request.getName());
							try {
								callBack.send(serviceRequest); // 通知有人上线
							} catch (Exception e) {
								//LOG.warn("回调发送消息给客户端异常", e);
							}
						}
					});
				}
				sendWebSocket(response.toJson());
				this.sessionId = requestId; // 记录会话id，当页面刷新或浏览器关闭时，注销掉此链路
			} else if (CODE.send_message.code.intValue() == request.getServiceId()) { // 客户端发送消息到聊天群
				String requestId = request.getRequestId();
				if (Strings.isNullOrEmpty(requestId)) {
					response.setIsSucc(false).setMessage("requestId不能为空");
				} else if (Strings.isNullOrEmpty(request.getName())) {
					response.setIsSucc(false).setMessage("name不能为空");
				} else if (Strings.isNullOrEmpty(request.getMessage())) {
					response.setIsSucc(false).setMessage("message不能为空");
				} else {
					response.setIsSucc(true).setMessage("发送消息成功");
					
					BananaService.bananaWatchMap.forEach((reqId, callBack) -> { // 将消息发送到所有机器
						Request serviceRequest = new Request();
						serviceRequest.setServiceId(CODE.receive_message.code);
						serviceRequest.setRequestId(requestId);
						serviceRequest.setName(request.getName());
						serviceRequest.setMessage(request.getMessage());
						try {
							callBack.send(serviceRequest);
						} catch (Exception e) {
							//LOG.warn("回调发送消息给客户端异常", e);
						}
					});
				}
				sendWebSocket(response.toJson());
			} else if (CODE.downline.code.intValue() == request.getServiceId()) { // 客户端下线
				String requestId = request.getRequestId();
				if (Strings.isNullOrEmpty(requestId)) {
					sendWebSocket(response.setIsSucc(false).setMessage("requestId不能为空").toJson());
				} else {
					BananaService.logout(requestId);
					response.setIsSucc(true).setMessage("下线成功");
					
					BananaService.notifyDownline(requestId); // 通知有人下线
					
					sendWebSocket(response.toJson());
				}
				
			} else {
				sendWebSocket(response.setIsSucc(false).setMessage("未知请求").toJson());
			}
		} catch (JsonSyntaxException e1) {
			//LOG.warn("Json解析异常", e1);
		} catch (Exception e2) {
			//LOG.error("处理Socket请求异常", e2);
		}
	}

	/**
	 * Http返回
	 * @param ctx
	 * @param request
	 * @param response
	 */
	private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
		System.out.println("sendHttpResponse:7");
		// 返回应答给客户端
		if (response.getStatus().code() != 200) {
			ByteBuf buf = Unpooled.copiedBuffer(response.getStatus().toString(), CharsetUtil.UTF_8);
			response.content().writeBytes(buf);
			buf.release();
			HttpHeaders.setContentLength(response, response.content().readableBytes());
		}

		// 如果是非Keep-Alive，关闭连接
		ChannelFuture f = ctx.channel().writeAndFlush(response);
		if (!HttpHeaders.isKeepAlive(request) || response.getStatus().code() != 200) {
			f.addListener(ChannelFutureListener.CLOSE);
		}
	}
	
	/**
	 * WebSocket返回
	 * @aram ctx
	 * param req
	 * param res
	 */
	public void sendWebSocket(String msg) throws Exception {
		System.out.println("sendWebSocket:8");
		if (this.handshaker == null || this.ctx == null || this.ctx.isRemoved()) {
			throw new Exception("尚未握手成功，无法向客户端发送WebSocket消息");
		}
		this.ctx.channel().write(new TextWebSocketFrame(msg));
		this.ctx.flush();
	}

}