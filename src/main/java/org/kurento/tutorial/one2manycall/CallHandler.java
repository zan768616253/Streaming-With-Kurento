/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.one2manycall;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Protocol handler for 1 to N video call communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
public class CallHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
    private static final Gson gson = new GsonBuilder().create();

    private final ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();

    @Autowired
    private KurentoClient kurento;

    private MediaPipeline pipeline;
    private UserSession presenterUserSession;
    private JsonObject candidate;
    private UserSession user;

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        log.debug("Incoming message from session '{}': {}", session.getId(), jsonMessage);

        switch (jsonMessage.get("id").getAsString()) {
            case "ps":
                try {
                    presenterScreen(session, jsonMessage);
                } catch (Throwable t) {
                    handleErrorResponse(t, session, "presenterScreenRes-ponse");
                }
                break;
            case "pc":
                try {
                    presenterCamera(session, jsonMessage);
                } catch (Throwable t) {
                    handleErrorResponse(t, session, "presenterCameraResponse");
                }
                break;
            case "vs":
                try {
                    viewerScreen(session, jsonMessage);
                } catch (Throwable t) {
                    handleErrorResponse(t, session, "viewerResponse");
                }
                break;
            case "vc":
                try {
                    viewerCamera(session, jsonMessage);
                } catch (Throwable t) {
                    handleErrorResponse(t, session, "viewerResponse");
                }
                break;
            case "onIceCandidateCamera":
                candidate = jsonMessage.get("candidate").getAsJsonObject();
                if (presenterUserSession != null) {
                    if (presenterUserSession.getSession() == session) {
                        user = presenterUserSession;
                    } else {
                        user = viewers.get(session.getId());
                    }
                }
                if (user != null) {
                    IceCandidate cand =
                            new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                                    .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
                    user.addCandidateCamera(cand);
                }
                break;
            case "onIceCandidateScreen": {
                candidate = jsonMessage.get("candidate").getAsJsonObject();
                if (presenterUserSession != null) {
                    if (presenterUserSession.getSession() == session) {
                        user = presenterUserSession;
                    } else {
                        user = viewers.get(session.getId());
                    }
                }
                if (user != null) {
                    IceCandidate cand =
                            new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                                    .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
                    user.addCandidateScreen(cand);
                }
                break;
            }
            case "stop":
                stop(session);
                break;
            default:
                break;
        }
    }

    private void handleErrorResponse(Throwable throwable, WebSocketSession session, String responseId)
            throws IOException {
        stop(session);
        log.error(throwable.getMessage(), throwable);
        JsonObject response = new JsonObject();
        response.addProperty("id", responseId);
        response.addProperty("response", "rejected");
        response.addProperty("message", throwable.getMessage());
        session.sendMessage(new TextMessage(response.toString()));
    }

    private synchronized void presenterCamera(final WebSocketSession session, final JsonObject jsonMessage)
            throws IOException {

        if (presenterUserSession == null) {
            presenterUserSession = new UserSession(session);
            pipeline = kurento.createMediaPipeline();
        }

        presenterUserSession.setWebRtcCameraEndpoint(new WebRtcEndpoint.Builder(pipeline).build());
        WebRtcEndpoint presenterWebRtc = presenterUserSession.getWebRtcCameraEndpoint();

        presenterWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

            @Override
            public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "iceCandidateCamera");
                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(response.toString()));
                    }
                } catch (IOException e) {
                    log.debug(e.getMessage());
                }
            }
        });

        String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
        String sdpAnswer = presenterWebRtc.processOffer(sdpOffer);

        JsonObject response = new JsonObject();
        response.addProperty("id", "presenterCameraResponse");
        response.addProperty("response", "accepted");
        response.addProperty("sdpAnswer", sdpAnswer);

        synchronized (session) {
            presenterUserSession.sendMessage(response);
        }
        presenterWebRtc.gatherCandidates();
    }

    private synchronized void presenterScreen(final WebSocketSession session, final JsonObject jsonMessage)
            throws IOException {

        if (presenterUserSession == null) {
            presenterUserSession = new UserSession(session);
            pipeline = kurento.createMediaPipeline();
        }

        presenterUserSession.setWebRtcScreenEndpoint(new WebRtcEndpoint.Builder(pipeline).build());
        WebRtcEndpoint presenterWebRtc = presenterUserSession.getWebRtcScreenEndpoint();

        presenterWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {
            @Override
            public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "iceCandidateScreen");
                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(response.toString()));
                    }
                } catch (IOException e) {
                    log.debug(e.getMessage());
                }
            }
        });

        String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
        String sdpAnswer = presenterWebRtc.processOffer(sdpOffer);

        JsonObject response = new JsonObject();
        response.addProperty("id", "presenterScreenResponse");
        response.addProperty("response", "accepted");
        response.addProperty("sdpAnswer", sdpAnswer);

        synchronized (session) {
            presenterUserSession.sendMessage(response);
        }
        presenterWebRtc.gatherCandidates();
    }

    private synchronized void viewerCamera(final WebSocketSession session, JsonObject jsonMessage)
            throws IOException {
        if (presenterUserSession == null) {
            JsonObject response = new JsonObject();
            response.addProperty("id", "viewerCameraResponse");
            response.addProperty("response", "rejected");
            response.addProperty("message",
                    "No active sender now. Become sender or . Try again later ...");
            session.sendMessage(new TextMessage(response.toString()));
        } else {
            UserSession viewer = viewers.get(session.getId());
            if (viewer == null) {
                viewer = new UserSession(session);
                viewers.put(session.getId(), viewer);
            }

            WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

            nextWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

                @Override
                public void onEvent(IceCandidateFoundEvent event) {
                    JsonObject response = new JsonObject();
                    response.addProperty("id", "iceCandidateViewerCamera");
                    response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(response.toString()));
                        }
                    } catch (IOException e) {
                        log.debug(e.getMessage());
                    }
                }
            });

            viewer.setWebRtcCameraEndpoint(nextWebRtc);
            presenterUserSession.getWebRtcCameraEndpoint().connect(nextWebRtc);
            String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
            String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

            JsonObject response = new JsonObject();
            response.addProperty("id", "viewerCameraResponse");
            response.addProperty("response", "accepted");
            response.addProperty("sdpAnswer", sdpAnswer);

            synchronized (session) {
                viewer.sendMessage(response);
            }
            nextWebRtc.gatherCandidates();
        }
    }

    private synchronized void viewerScreen(final WebSocketSession session, JsonObject jsonMessage)
            throws IOException {
        if (presenterUserSession == null) {
            JsonObject response = new JsonObject();
            response.addProperty("id", "viewerScreenResponse");
            response.addProperty("response", "rejected");
            response.addProperty("message",
                    "No active sender now. Become sender or . Try again later ...");
            session.sendMessage(new TextMessage(response.toString()));
        } else {
            UserSession viewer = viewers.get(session.getId());
            if (viewer == null) {
                viewer = new UserSession(session);
                viewers.put(session.getId(), viewer);
            }

            WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

            nextWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

                @Override
                public void onEvent(IceCandidateFoundEvent event) {
                    JsonObject response = new JsonObject();
                    response.addProperty("id", "iceCandidateViewerScreen");
                    response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(response.toString()));
                        }
                    } catch (IOException e) {
                        log.debug(e.getMessage());
                    }
                }
            });

            viewer.setWebRtcScreenEndpoint(nextWebRtc);
            presenterUserSession.getWebRtcScreenEndpoint().connect(nextWebRtc);
            String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
            String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

            JsonObject response = new JsonObject();
            response.addProperty("id", "viewerScreenResponse");
            response.addProperty("response", "accepted");
            response.addProperty("sdpAnswer", sdpAnswer);

            synchronized (session) {
                viewer.sendMessage(response);
            }
            nextWebRtc.gatherCandidates();
        }
    }

    private synchronized void stop(WebSocketSession session) throws IOException {
        String sessionId = session.getId();
        if (presenterUserSession != null && presenterUserSession.getSession().getId().equals(sessionId)) {
            for (UserSession viewer : viewers.values()) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "stopCommunication");
                viewer.sendMessage(response);
            }

            log.info("Releasing media pipeline");
            if (pipeline != null) {
                pipeline.release();
            }
            pipeline = null;
            presenterUserSession = null;
        } else if (viewers.containsKey(sessionId)) {
            if (viewers.get(sessionId).getWebRtcScreenEndpoint() != null) {
                viewers.get(sessionId).getWebRtcScreenEndpoint().release();
            }
            if (viewers.get(sessionId).getWebRtcCameraEndpoint() != null) {
                viewers.get(sessionId).getWebRtcCameraEndpoint().release();
            }
            viewers.remove(sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        stop(session);
    }

}
