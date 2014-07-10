package com.devicehive.client.impl.context;

import com.devicehive.client.ConnectionLostCallback;
import com.devicehive.client.ConnectionRestoredCallback;
import com.devicehive.client.HiveMessageHandler;
import com.devicehive.client.impl.json.GsonFactory;
import com.devicehive.client.impl.json.strategies.JsonPolicyDef;
import com.devicehive.client.impl.util.Messages;
import com.devicehive.client.impl.websocket.HiveWebsocketHandler;
import com.devicehive.client.impl.websocket.SessionMonitor;
import com.devicehive.client.impl.websocket.SimpleWebsocketResponse;
import com.devicehive.client.model.ApiInfo;
import com.devicehive.client.model.DeviceCommand;
import com.devicehive.client.model.DeviceNotification;
import com.devicehive.client.model.SubscriptionFilter;
import com.devicehive.client.model.exceptions.HiveClientException;
import com.devicehive.client.model.exceptions.HiveException;
import com.devicehive.client.model.exceptions.HiveServerException;
import com.devicehive.client.model.exceptions.InternalHiveClientException;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.glassfish.tyrus.client.ClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;


import static com.devicehive.client.impl.json.strategies.JsonPolicyDef.Policy.*;
import static com.devicehive.client.impl.websocket.JsonEncoder.*;
import static javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;


public class WebsocketAgent extends RestAgent {

    private static Logger logger = LoggerFactory.getLogger(WebsocketAgent.class);

    private static final String REQUEST_ID_MEMBER = "requestId";
    private static final Long WAIT_TIMEOUT = 1L;

    private final String role;
    private final ConcurrentMap<String, String> serverToLocalSubIdMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, HiveMessageHandler<DeviceCommand>> commandUpdatesHandlerStorage =
            new ConcurrentHashMap<>();

    private final ExecutorService subscriptionExecutor = Executors.newFixedThreadPool(50);


    private final ConcurrentMap<String, SettableFuture<JsonObject>> websocketResponsesMap = new ConcurrentHashMap<>();
    private Session currentSession;


    public WebsocketAgent(ConnectionLostCallback connectionLostCallback, ConnectionRestoredCallback connectionRestoredCallback, URI restUri, String role) {
        super(connectionLostCallback, connectionRestoredCallback, restUri);
        this.role = role;
    }

    private ClientManager getClient() {
        ClientManager client = ClientManager.createClient();
        ClientManager.ReconnectHandler reconnectHandler = new ClientManager.ReconnectHandler() {
            @Override
            public boolean onDisconnect(CloseReason closeReason) {
                boolean lost = CloseReason.CloseCodes.NORMAL_CLOSURE != closeReason.getCloseCode();
                if (lost) {
                    connectionStateExecutor.submit(new Runnable() {
                        @Override
                        public void run() {
                            if (connectionLostCallback != null) {
                                connectionLostCallback.connectionLost();
                            }
                        }
                    });
                }
                return lost;
            }

            @Override
            public boolean onConnectFailure(Exception exception) {
                return super.onConnectFailure(exception);
            }
        };
        client.getProperties().put(ClientManager.RECONNECT_HANDLER, reconnectHandler);
        return client;
    }

    @Override
    protected void doConnect() throws HiveException {
        super.doConnect();
        String basicUrl = super.getInfo().getWebSocketServerUrl();
        if (basicUrl == null) {
            throw new HiveException("Can not connect to websockets, endpoint URL is not provided by server");
        }
        URI wsUri = URI.create(basicUrl + "/" + role);
        try {
            getClient().connectToServer(new Endpoint() {
                @Override
                public void onOpen(final Session session, EndpointConfig config) {
                    try {
                        stateLock.writeLock().lock();
                        logger.info("[onOpen] User session: {}", session);
                        SessionMonitor sessionMonitor = new SessionMonitor(session);
                        session.getUserProperties().put(SessionMonitor.SESSION_MONITOR_KEY, sessionMonitor);
                        session.addMessageHandler(new HiveWebsocketHandler(WebsocketAgent.this, websocketResponsesMap));
                        boolean reconnect = currentSession != null;
                        currentSession = session;
                        if (reconnect) {

                            try {
                                resubscribe();
                                connectionStateExecutor.submit(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (connectionRestoredCallback != null) {
                                            connectionRestoredCallback.connectionRestored();
                                        }
                                    }
                                });
                            } catch (final HiveException he) {
                                logger.error("Can not restore session context", he);
                                connectionStateExecutor.submit(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Can not restore session context"));
                                        } catch (IOException e1) {
                                            logger.error("Can not close session", e1);
                                        }
                                    }
                                });
                            }
                        }
                    } finally {
                        stateLock.writeLock().unlock();
                    }
                }

                @Override
                public void onClose(Session session, CloseReason reason) {
                    try {
                        stateLock.writeLock().lock();
                        logger.info("[onClose] Websocket client closed. Reason: " + reason.getReasonPhrase() + "; Code: " +
                                reason.getCloseCode
                                        ().getCode());
                        SessionMonitor sessionMonitor =
                                (SessionMonitor) session.getUserProperties().get(SessionMonitor.SESSION_MONITOR_KEY);
                        if (sessionMonitor != null) {
                            sessionMonitor.close();
                        }
                    } finally {
                        stateLock.writeLock().unlock();
                    }
                }

                @Override
                public void onError(Session session, Throwable thr) {
                    logger.error("[onError] ", thr);
                }
            }, ClientEndpointConfig.Builder.create().build(), wsUri);
        } catch (IOException | DeploymentException e) {
            throw new HiveException("Can not connect to websockets", e);
        }
    }

    @Override
    protected void doDisconnect() {
        try {
            if (currentSession != null) {
                currentSession.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Bye."));
            }
        } catch (IOException e) {
            logger.error("Error closing websocket session", e);
        }
        super.doDisconnect();
    }


    private void resubscribe() throws HiveException {
        HivePrincipal principal = getHivePrincipal();
        if (principal != null) {
            authenticate(principal);
        }
        Set<String> commandSubIds = new HashSet<>(this.getCommandSubscriptionsStorage().keySet());
        for (String subId : commandSubIds) {
            SubscriptionDescriptor<DeviceCommand> sub = getCommandsSubscriptionDescriptor(subId);
            serverToLocalSubIdMap.put(sendSubscribeForCommands(sub.getFilter()), subId);
        }

        Set<String> notificationSubIds = new HashSet<>(this.getNotificationSubscriptionsStorage().keySet());
        for (String subId : notificationSubIds) {
            SubscriptionDescriptor<DeviceNotification> sub = getNotificationsSubscriptionDescriptor(subId);
            serverToLocalSubIdMap.put(sendSubscribeForNotifications(sub.getFilter()), subId);
        }
    }

    /**
     * Sends message to server
     *
     * @param message some HiveEntity object in JSON
     */
    public void sendMessage(JsonObject message) throws HiveException {
        String requestId = rawSend(message);
        websocketResponsesMap.put(requestId, SettableFuture.<JsonObject>create());
        processResponse(requestId);
    }

    /**
     * Sends message to server
     *
     * @param message            some HiveEntity object in JSON
     * @param responseMemberName in response name of field that contains required object
     * @param typeOfResponse     type of response
     * @param policy             policy that declares exclusion strategy for received object
     * @return instance of typeOfResponse, that represents server's response
     */
    public <T> T sendMessage(JsonObject message, String responseMemberName, Type typeOfResponse,
                             JsonPolicyDef.Policy policy) throws HiveException {
        String requestId = rawSend(message);
        websocketResponsesMap.put(requestId, SettableFuture.<JsonObject>create());
        return processResponse(requestId, responseMemberName, typeOfResponse, policy);
    }

    private String rawSend(JsonObject message) {
        String requestId = UUID.randomUUID().toString();
        message.addProperty(REQUEST_ID_MEMBER, requestId);
        try {
            stateLock.readLock().lock();
            currentSession.getAsyncRemote().sendObject(message);
            return requestId;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private void processResponse(final String requestId) throws HiveException {
        try {
            JsonObject result = websocketResponsesMap.get(requestId).get(WAIT_TIMEOUT, TimeUnit.MINUTES);

            if (result != null) {
                Gson gson = GsonFactory.createGson();
                SimpleWebsocketResponse response;
                try {
                    response = gson.fromJson(result, SimpleWebsocketResponse.class);
                } catch (JsonSyntaxException e) {
                    throw new HiveServerException(Messages.INCORRECT_RESPONSE_TYPE, 500);
                }
                if (response.getStatus().equals(Constants.EXPECTED_RESPONSE_STATUS)) {
                    logger.debug("Request with id:" + requestId + "proceed successfully");
                    return;
                }
                Response.Status.Family errorFamily = Response.Status.Family.familyOf(response.getCode());
                switch (errorFamily) {
                    case SERVER_ERROR:
                        logger.warn(
                                "Request id: " + requestId + ". Error message:" + response.getError() + ". Status code:"
                                        + response.getCode()
                        );
                        throw new HiveServerException(response.getError(), response.getCode());
                    case CLIENT_ERROR:
                        logger.warn(
                                "Request id: " + requestId + ". Error message:" + response.getError() + ". Status code:"
                                        + response.getCode()
                        );
                        throw new HiveClientException(response.getError(), response.getCode());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HiveClientException("Interrupted", e);
        } catch (TimeoutException e) {
            throw new HiveServerException(Messages.NO_RESPONSES_FROM_SERVER, SERVICE_UNAVAILABLE.getStatusCode());
        } catch (ExecutionException e) {
            throw new InternalHiveClientException(e.getMessage(), e.getCause());
        } finally {
            websocketResponsesMap.remove(requestId);
        }
    }

    private <T> T processResponse(final String requestId, final String responseMemberName, final Type typeOfResponse,
                                  final JsonPolicyDef.Policy receivePolicy) throws HiveException {
        try {
            JsonObject result = websocketResponsesMap.get(requestId).get(WAIT_TIMEOUT, TimeUnit.MINUTES);
            if (result != null) {
                if (result.get(Constants.STATUS).getAsString().equals(Constants.EXPECTED_RESPONSE_STATUS)) {
                    logger.debug("Request with id:" + requestId + "proceed successfully");
                } else {
                    Response.Status.Family errorFamily = Response.Status.Family.familyOf(result.get(Constants.CODE).getAsInt());
                    String error = null;
                    if (result.get(Constants.ERROR) instanceof JsonPrimitive)
                        error = result.get(Constants.ERROR).getAsString();
                    Integer code = null;
                    if (result.get(Constants.CODE) instanceof JsonPrimitive)
                        code = result.get(Constants.CODE).getAsInt();
                    switch (errorFamily) {
                        case SERVER_ERROR:
                            logger.warn("Request id: " + requestId + ". Error message:" + error + ". Status " +
                                    "code:" + code);
                            throw new HiveServerException(error, code);
                        case CLIENT_ERROR:
                            logger.warn("Request id: " + requestId + ". Error message:" + error + ". Status " +
                                    "code:" + code);
                            throw new HiveClientException(error, code);
                    }
                }
                Gson gson = GsonFactory.createGson(receivePolicy);
                T response;
                try {
                    response = gson.fromJson(result.get(responseMemberName), typeOfResponse);
                } catch (JsonSyntaxException e) {
                    throw new InternalHiveClientException("Wrong type of response!");
                }
                return response;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HiveClientException("Interrupted", e);
        } catch (TimeoutException e) {
            noResponseAction();
        } catch (ExecutionException e) {
            throw new InternalHiveClientException(e.getMessage(), e.getCause());
        } finally {
            websocketResponsesMap.remove(requestId);
        }
        return null;
    }

    private void noResponseAction() throws HiveServerException {
        throw new HiveServerException(Messages.NO_RESPONSES_FROM_SERVER, SERVICE_UNAVAILABLE.getStatusCode());
    }


    @Override
    public ApiInfo getInfo() throws HiveException {
        JsonObject request = new JsonObject();
        request.addProperty(ACTION_MEMBER, "server/info");
        String requestId = UUID.randomUUID().toString();
        request.addProperty(REQUEST_ID_MEMBER, requestId);
        ApiInfo apiInfo = sendMessage(request, "info", ApiInfo.class, null);
        String restUrl = apiInfo.getRestServerUrl();
        apiInfo = super.getInfo();
        apiInfo.setRestServerUrl(restUrl);
        return apiInfo;
    }

    @Override
    public String subscribeForCommands(SubscriptionFilter newFilter,
                                       HiveMessageHandler<DeviceCommand> handler) throws HiveException {
        stateLock.writeLock().lock();
        try {
            String localId = UUID.randomUUID().toString();
            addCommandsSubscription(localId, new SubscriptionDescriptor<>(handler, newFilter));
            serverToLocalSubIdMap.put(sendSubscribeForCommands(newFilter), localId);
            return localId;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private String sendSubscribeForCommands(SubscriptionFilter newFilter) throws HiveException {
        Gson gson = GsonFactory.createGson();
        JsonObject request = new JsonObject();
        request.addProperty(ACTION_MEMBER, "command/subscribe");
        request.add("filter", gson.toJsonTree(newFilter));
        return sendMessage(request, SUBSCRIPTION_ID, String.class, null);
    }


    @Override
    public String subscribeForNotifications(SubscriptionFilter newFilter,
                                            HiveMessageHandler<DeviceNotification> handler) throws HiveException {
        stateLock.writeLock().lock();
        try {
            String localId = UUID.randomUUID().toString();
            addNotificationsSubscription(localId, new SubscriptionDescriptor<>(handler, newFilter));
            serverToLocalSubIdMap.put(sendSubscribeForNotifications(newFilter), localId);
            return localId;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private String sendSubscribeForNotifications(SubscriptionFilter newFilter) throws HiveException {
        Gson gson = GsonFactory.createGson();
        JsonObject request = new JsonObject();
        request.addProperty(ACTION_MEMBER, "notification/subscribe");
        request.add("filter", gson.toJsonTree(newFilter));
        return sendMessage(request, SUBSCRIPTION_ID, String.class, null);
    }


    @Override
    public void unsubscribeFromCommands(String subId) throws HiveException {
        stateLock.writeLock().lock();
        try {
            removeCommandsSubscription(subId);
            JsonObject request = new JsonObject();
            request.addProperty(ACTION_MEMBER, "command/unsubscribe");
            request.addProperty(SUBSCRIPTION_ID, subId);
            sendMessage(request);
        } finally {
            stateLock.writeLock().unlock();
        }
    }


    public void unsubscribeFromNotifications(String subId) throws HiveException {
        stateLock.writeLock().lock();
        try {
            removeNotificationsSubscription(subId);
            JsonObject request = new JsonObject();
            request.addProperty(ACTION_MEMBER, "notification/unsubscribe");
            request.addProperty(SUBSCRIPTION_ID, subId);
            sendMessage(request);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public void authenticate(HivePrincipal principal) throws HiveException {
        JsonObject request = new JsonObject();
        request.addProperty(ACTION_MEMBER, "authenticate");
        if (principal.getUser() != null) {
            request.addProperty("login", principal.getUser().getKey());
            request.addProperty("password", principal.getUser().getValue());
        } else if (principal.getDevice() != null) {
            request.addProperty("deviceId", principal.getDevice().getKey());
            request.addProperty("deviceKey", principal.getDevice().getValue());
        } else if (principal.getAccessKey() != null) {
            request.addProperty("accessKey", principal.getAccessKey());
        } else {
            throw new IllegalArgumentException(Messages.INVALID_HIVE_PRINCIPAL);
        }

        stateLock.writeLock().lock();
        try {
            super.authenticate(principal);
            sendMessage(request);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void addCommandUpdateSubscription(Long commandId, String guid, HiveMessageHandler<DeviceCommand> handler) {
        stateLock.writeLock().lock();
        try {
            commandUpdatesHandlerStorage.put(commandId, handler);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void handleServerMessage(final JsonObject jsonMessage) {
        subscriptionExecutor.submit(new Runnable() {
            @Override
            public void run() {
                stateLock.readLock().lock();
                try {
                    switch (jsonMessage.get(ACTION_MEMBER).getAsString()) {
                        case COMMAND_INSERT:
                            Gson commandInsertGson = GsonFactory.createGson(COMMAND_LISTED);
                            DeviceCommand commandInsert = commandInsertGson.fromJson(jsonMessage.getAsJsonObject(COMMAND_MEMBER),
                                    DeviceCommand.class);
                            String localCommandSubId = serverToLocalSubIdMap.get(jsonMessage.get(SUBSCRIPTION_ID).getAsString());
                            getCommandsSubscriptionDescriptor(localCommandSubId).handleMessage(commandInsert);
                            break;
                        case COMMAND_UPDATE:
                            Gson commandUpdateGson = GsonFactory.createGson(COMMAND_UPDATE_TO_CLIENT);
                            DeviceCommand commandUpdated = commandUpdateGson.fromJson(jsonMessage.getAsJsonObject
                                    (COMMAND_MEMBER), DeviceCommand.class);
                            if (commandUpdatesHandlerStorage.get(commandUpdated.getId()) != null) {
                                commandUpdatesHandlerStorage.remove(commandUpdated.getId()).handle(commandUpdated);
                            }
                            break;
                        case NOTIFICATION_INSERT:
                            Gson notificationsGson = GsonFactory.createGson(NOTIFICATION_TO_CLIENT);
                            DeviceNotification notification = notificationsGson.fromJson(jsonMessage.getAsJsonObject
                                    (NOTIFICATION_MEMBER), DeviceNotification.class);
                            String localNotifSubId = serverToLocalSubIdMap.get(jsonMessage.get(SUBSCRIPTION_ID).getAsString());
                            getNotificationsSubscriptionDescriptor(localNotifSubId).handleMessage(notification);
                            break;
                        default: //unknown request
                            logger.error("Server sent unknown message {}", jsonMessage);
                    }
                } finally {
                    stateLock.readLock().unlock();
                }
            }
        });
    }
}
