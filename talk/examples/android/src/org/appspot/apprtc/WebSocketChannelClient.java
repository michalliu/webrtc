/*
 * libjingle
 * Copyright 2014, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.appspot.apprtc;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketException;
import de.tavendo.autobahn.WebSocket.WebSocketConnectionObserver;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedList;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * WebSocket client implementation.
 * For proper synchronization all methods should be called from UI thread
 * and all WebSocket events are delivered on UI thread as well.
 */

public class WebSocketChannelClient {
  private final String TAG = "WSChannelRTCClient";
  private final WebSocketChannelEvents events;
  private final Handler uiHandler;
  private WebSocketConnection ws;
  private WebSocketObserver wsObserver;
  private String wsServerUrl;
  private String postServerUrl;
  private String roomID;
  private String clientID;
  private WebSocketConnectionState state;
  // Http post/delete message queue. Messages are added from UI thread in
  // post() and disconnect() calls. Messages are consumed by AsyncTask's
  // background thread.
  private LinkedList<WsHttpMessage> wsHttpQueue;
  // WebSocket send queue. Messages are added to the queue when WebSocket
  // client is not registered and are consumed in register() call.
  private LinkedList<String> wsSendQueue;

  public enum WebSocketConnectionState {
    NEW, CONNECTED, REGISTERED, CLOSED, ERROR
  };

  /**
   * Callback interface for messages delivered on WebSocket.
   * All events are invoked from UI thread.
   */
  public interface WebSocketChannelEvents {
    public void onWebSocketOpen();
    public void onWebSocketMessage(final String message);
    public void onWebSocketClose();
    public void onWebSocketError(final String description);
  }

  public WebSocketChannelClient(WebSocketChannelEvents events) {
    this.events = events;
    uiHandler = new Handler(Looper.getMainLooper());
    roomID = null;
    clientID = null;
    wsHttpQueue = new LinkedList<WsHttpMessage>();
    wsSendQueue = new LinkedList<String>();
    state = WebSocketConnectionState.NEW;
  }

  public WebSocketConnectionState getState() {
    return state;
  }

  public void connect(String wsUrl, String postUrl) {
    if (state != WebSocketConnectionState.NEW) {
      Log.e(TAG, "WebSocket is already connected.");
      return;
    }
    Log.d(TAG, "Connecting WebSocket to: " + wsUrl + ". Post URL: " + postUrl);

    ws = new WebSocketConnection();
    wsObserver = new WebSocketObserver();
    try {
      wsServerUrl = wsUrl;
      postServerUrl = postUrl;
      ws.connect(new URI(wsServerUrl), wsObserver);
    } catch (URISyntaxException e) {
      reportError("URI error: " + e.getMessage());
    } catch (WebSocketException e) {
      reportError("WebSocket connection error: " + e.getMessage());
    }
  }

  public void setClientParameters(String roomID, String clientID) {
    this.roomID = roomID;
    this.clientID = clientID;
  }

  public void register() {
    if (state != WebSocketConnectionState.CONNECTED) {
      Log.w(TAG, "WebSocket register() in state " + state);
      return;
    }
    if (roomID == null || clientID == null) {
      Log.w(TAG, "Call WebSocket register() without setting client ID");
      return;
    }
    JSONObject json = new JSONObject();
    try {
      json.put("cmd", "register");
      json.put("roomid", roomID);
      json.put("clientid", clientID);
      Log.d(TAG, "WS SEND: " + json.toString());
      ws.sendTextMessage(json.toString());
      state = WebSocketConnectionState.REGISTERED;
      // Send any previously accumulated messages.
      synchronized(wsSendQueue) {
        for (String sendMessage : wsSendQueue) {
          send(sendMessage);
        }
        wsSendQueue.clear();
      }
    } catch (JSONException e) {
      reportError("WebSocket register JSON error: " + e.getMessage());
    }
  }

  public void send(String message) {
    switch (state) {
      case NEW:
      case CONNECTED:
        // Store outgoing messages and send them after websocket client
        // is registered.
        Log.d(TAG, "WS ACC: " + message);
        synchronized(wsSendQueue) {
          wsSendQueue.add(message);
          return;
        }
      case ERROR:
      case CLOSED:
        Log.e(TAG, "WebSocket send() in error or closed state : " + message);
        return;
      case REGISTERED:
        JSONObject json = new JSONObject();
        try {
          json.put("cmd", "send");
          json.put("msg", message);
          message = json.toString();
          Log.d(TAG, "WS SEND: " + message);
          ws.sendTextMessage(message);
        } catch (JSONException e) {
          reportError("WebSocket send JSON error: " + e.getMessage());
        }
        break;
    }
    return;
  }

  // This call can be used to send WebSocket messages before WebSocket
  // connection is opened. However for now this way of sending messages
  // is not used until possible race condition of arriving ice candidates
  // send through websocket before SDP answer sent through http post will be
  // resolved.
  public void post(String message) {
    synchronized (wsHttpQueue) {
      wsHttpQueue.add(new WsHttpMessage("POST", message));
    }
    requestHttpQueueDrainInBackground();
  }

  public void disconnect() {
    Log.d(TAG, "Disonnect WebSocket. State: " + state);
    if (state == WebSocketConnectionState.REGISTERED) {
      send("{\"type\": \"bye\"}");
      state = WebSocketConnectionState.CONNECTED;
    }
    // Close WebSocket in CONNECTED or ERROR states only.
    if (state == WebSocketConnectionState.CONNECTED ||
        state == WebSocketConnectionState.ERROR) {
      ws.disconnect();

      // Send DELETE to http WebSocket server.
      synchronized (wsHttpQueue) {
        wsHttpQueue.clear();
        wsHttpQueue.add(new WsHttpMessage("DELETE", ""));
      }
      requestHttpQueueDrainInBackground();

      state = WebSocketConnectionState.CLOSED;
    }
  }

  private void reportError(final String errorMessage) {
    Log.e(TAG, errorMessage);
    uiHandler.post(new Runnable() {
      public void run() {
        if (state != WebSocketConnectionState.ERROR) {
          state = WebSocketConnectionState.ERROR;
          events.onWebSocketError(errorMessage);
        }
      }
    });
  }

  private class WsHttpMessage {
    WsHttpMessage(String method, String message) {
      this.method = method;
      this.message = message;
    }
    public final String method;
    public final String message;
  }

  // TODO(glaznev): This is not good implementation due to discrepancy
  // between JS encodeURIComponent() and Java URLEncoder.encode().
  // Remove this once WebSocket server will switch to a different encoding.
  private String encodeURIComponent(String s) {
    String result = null;
    try {
      result = URLEncoder.encode(s, "UTF-8")
         .replaceAll("\\+", "%20")
         .replaceAll("\\%21", "!")
         .replaceAll("\\%27", "'")
         .replaceAll("\\%28", "(")
         .replaceAll("\\%29", ")")
         .replaceAll("\\%7E", "~");
    } catch (UnsupportedEncodingException e) {
      result = s;
    }
    return result;
  }

  // Request an attempt to drain the send queue, on a background thread.
  private void requestHttpQueueDrainInBackground() {
    (new AsyncTask<Void, Void, Void>() {
      public Void doInBackground(Void... unused) {
        maybeDrainWsHttpQueue();
        return null;
      }
    }).execute();
  }

  // Send all queued websocket messages.
  private void maybeDrainWsHttpQueue() {
    synchronized (wsHttpQueue) {
      if (roomID == null || clientID == null) {
        return;
      }
      try {
        for (WsHttpMessage wsHttpMessage : wsHttpQueue) {
          // Send POST request.
          Log.d(TAG, "WS " + wsHttpMessage.method + " : " +
              wsHttpMessage.message);
          String postUrl = postServerUrl + roomID + "/" + clientID;
          HttpURLConnection connection =
              (HttpURLConnection) new URL(postUrl).openConnection();
          connection.setDoOutput(true);
          connection.setRequestProperty(
              "Content-type", "application/x-www-form-urlencoded");
          connection.setRequestMethod(wsHttpMessage.method);
          if (wsHttpMessage.message.length() > 0) {
            String message = "msg=" + encodeURIComponent(wsHttpMessage.message);
            connection.getOutputStream().write(message.getBytes("UTF-8"));
          }
          String replyHeader = connection.getHeaderField(null);
          if (!replyHeader.startsWith("HTTP/1.1 200 ")) {
            reportError("Non-200 response to " + wsHttpMessage.method + " : " +
                connection.getHeaderField(null));
          }
        }
      } catch (IOException e) {
        reportError("WS POST error: " + e.getMessage());
      }
      wsHttpQueue.clear();
    }
  }

  private class WebSocketObserver implements WebSocketConnectionObserver {
    @Override
    public void onOpen() {
      Log.d(TAG, "WebSocket connection opened to: " + wsServerUrl);
      uiHandler.post(new Runnable() {
        public void run() {
          state = WebSocketConnectionState.CONNECTED;
          events.onWebSocketOpen();
        }
      });
    }

    @Override
    public void onClose(WebSocketCloseNotification code, String reason) {
      Log.d(TAG, "WebSocket connection closed. Code: " + code +
          ". Reason: " + reason);
      uiHandler.post(new Runnable() {
        public void run() {
          if (state != WebSocketConnectionState.CLOSED) {
            state = WebSocketConnectionState.CLOSED;
            events.onWebSocketClose();
          }
        }
      });
    }

    @Override
    public void onTextMessage(String payload) {
      Log.d(TAG, "WS GET: " + payload);
      final String message = payload;
      uiHandler.post(new Runnable() {
        public void run() {
          if (state == WebSocketConnectionState.CONNECTED ||
              state == WebSocketConnectionState.REGISTERED) {
            events.onWebSocketMessage(message);
          }
        }
      });
    }

    @Override
    public void onRawTextMessage(byte[] payload) {
    }

    @Override
    public void onBinaryMessage(byte[] payload) {
    }
  }

}
