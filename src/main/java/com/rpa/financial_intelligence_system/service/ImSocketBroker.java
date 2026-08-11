package com.rpa.financial_intelligence_system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ImSocketBroker extends TextWebSocketHandler {
 private final ObjectMapper mapper; private final Map<String,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
 public ImSocketBroker(ObjectMapper mapper){this.mapper=mapper;}
 @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{String user=(String)session.getAttributes().get("username");if(user==null){session.close(CloseStatus.NOT_ACCEPTABLE);return;}WebSocketSession safe=new ConcurrentWebSocketSessionDecorator(session,10_000,512*1024);session.getAttributes().put("safeSession",safe);sessions.computeIfAbsent(user,k->ConcurrentHashMap.newKeySet()).add(safe);send(user,Map.of("event","CONNECTED","username",user));}
 @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){if("ping".equalsIgnoreCase(message.getPayload()))try{session.sendMessage(new TextMessage("{\"event\":\"PONG\"}"));}catch(IOException ignored){}}
 @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){String user=(String)session.getAttributes().get("username");WebSocketSession safe=(WebSocketSession)session.getAttributes().get("safeSession");if(user!=null&&safe!=null){Set<WebSocketSession> set=sessions.get(user);if(set!=null){set.remove(safe);if(set.isEmpty())sessions.remove(user);}}}
 @Override public void handleTransportError(WebSocketSession session,Throwable error)throws Exception{if(session.isOpen())session.close(CloseStatus.SERVER_ERROR);}
 public void send(String username,Object payload){Set<WebSocketSession> set=sessions.get(username);if(set==null)return;try{String json=mapper.writeValueAsString(payload);set.removeIf(s->{try{if(!s.isOpen())return true;s.sendMessage(new TextMessage(json));return false;}catch(Exception e){return true;}});}catch(Exception ignored){}}
 public void send(Collection<String> usernames,Object payload){usernames.forEach(user->send(user,payload));}
 public boolean connected(String username){return sessions.containsKey(username)&&!sessions.get(username).isEmpty();}
}
