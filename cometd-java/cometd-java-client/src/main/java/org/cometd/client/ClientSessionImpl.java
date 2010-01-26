package org.cometd.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.BayeuxClient.Extension;
import org.cometd.client.transport.AbstractTransportListener;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ClientSessionImpl implements ClientSession
{
    private enum State
    {
        HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED
    }
    
    private final BayeuxClientImpl _bayeux;
    private final String[] _servers;
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final List<ClientSessionListener> _listeners = new CopyOnWriteArrayList<ClientSessionListener>();
    private final Queue<Message> _queue = new ConcurrentLinkedQueue<Message>();
   
    private final AttributesMap _attributes = new AttributesMap();
    private final ConcurrentMap<String, ClientSessionChannel> _channels = new ConcurrentHashMap<String, ClientSessionChannel>();
    private final AtomicInteger _batch = new AtomicInteger();
    private final TransportListener _transportListener = new Listener();   
    private final AtomicInteger _messageIds = new AtomicInteger();
    
    private volatile String _clientId;
    private volatile ClientTransport _transport;
    private volatile int _server;
    private volatile Map<String,Object> _advice;
    private volatile State _state = State.DISCONNECTED;
    private volatile ScheduledFuture<?> _task;

    
    protected ClientSessionImpl(BayeuxClientImpl bayeux, String... servers)
    {
        _bayeux=bayeux;
        _servers=servers;
    }
    
    @Override
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    @Override
    public void addListener(ClientSessionListener listener)
    {
        _listeners.add(listener);
    }

    private void updateState(State newState)
    {
        Log.debug("State change: {} -> {}", _state, newState);
        this._state = newState;
    }
    
    @Override
    public SessionChannel getChannel(String channelId)
    {
        ClientSessionChannel channel = _channels.get(channelId);
        if (channel==null)
        {
            ClientSessionChannel new_channel=new ClientSessionChannel(channelId);
            channel=_channels.putIfAbsent(channelId,new_channel);
            if (channel==null)
                channel=new_channel;
        }
        return channel;
    }

    @Override
    public void handshake(boolean async) throws IOException
    {
        if (_clientId!=null)
            throw new IllegalStateException();
        
        Message.Mutable message = newMessage();
        message.setChannelId(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD,_bayeux.getAllowedTransports());
        message.put(Message.VERSION_FIELD, BayeuxClientImpl.BAYEUX_VERSION);
        doSend(message);
        
        if (!async)
        {
            // wait for response
        }
        
    }

    @Override
    public void removeListener(ClientSessionListener listener)
    {
        _listeners.remove(listener);
    }

    @Override
    public void batch(Runnable batch)
    {
        startBatch();
        try
        {
            batch.run();
        }
        finally
        {
            endBatch();
        }
    }

    @Override
    public void disconnect()
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void endBatch()
    {
        if (_batch.decrementAndGet()==0)
        {
            int size=_queue.size();
            while(size-->0)
            {
                Message message = _queue.poll();
                doSend(message);
            }
        }
    }
    
    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNames()
    {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public String getId()
    {
        return _clientId;
    }

    @Override
    public boolean isConnected()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Object removeAttribute(String name)
    {
        Object value = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return value;
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }

    @Override
    public void startBatch()
    {
        _batch.incrementAndGet();
    }
    
    protected Message.Mutable newMessage()
    {
        if (_transport!=null)
            return _transport.newMessage();
        return new HashMapMessage();
    }
    
    protected void send(Message message)
    {
        if (_batch.get()>0)
            _queue.add(message);
        else
            doSend(message);
    }
    
    protected void doSend(Message message)
    {
        _transport.send(_servers[_server], message);
    }
    
    protected void receive(List<Message.Mutable> incomingMessages)
    {
        List<Message.Mutable> messages = applyIncomingExtensions(incomingMessages);

        for (Message message : messages)
        {
            Map<String, Object> advice = message.getAdvice();
            if (advice != null)
                this._advice = advice;

            String channelId = message.getChannelId();
            if (channelId == null)
            {
                Log.info("Ignoring invalid bayeux message, missing channel: {}", message);
                continue;
            }

            Boolean successfulField = (Boolean)message.get(Message.SUCCESSFUL_FIELD);
            boolean successful = successfulField != null && successfulField;
            
            if (Channel.META_HANDSHAKE.equals(channelId))
            {
                if (_state != State.HANDSHAKING)
                    throw new IllegalStateException();

                if (successful)
                    processHandshake(message);
                else
                    processUnsuccessful(message);
            }
            else if (Channel.META_CONNECT.equals(channelId))
            {
                if (_state != State.CONNECTED && _state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new UnsupportedOperationException();

                if (successful)
                    processConnect(message);
                else
                    processUnsuccessful(message);
            }
            else if (Channel.META_DISCONNECT.equals(channelId))
            {
                if (_state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new UnsupportedOperationException();

                if (successful)
                    processDisconnect(message);
                else
                    processUnsuccessful(message);
            }
            else
            {
                if (successful)
                    processMessage(message);
                else
                    processUnsuccessful(message);
            }
        }
    }
    private List<Message.Mutable> applyIncomingExtensions(List<Message.Mutable> messages)
    {
        List<Message.Mutable> result = new ArrayList<Message.Mutable>();
        for (Message.Mutable message : messages)
        {
            for (Extension extension : _extensions)
            {
                try
                {
                    boolean advance;

                    if (message.isMeta())
                        advance = extension.rcvMeta(this, message);
                    else
                        advance = extension.rcv(this, message);

                    if (!advance)
                    {
                        Log.debug("Extension {} signalled to skip message {}", extension, message);
                        message = null;
                        break;
                    }
                }
                catch (Exception x)
                {
                    Log.debug("Exception while invoking extension " + extension, x);
                }
            }
            if (message != null)
                result.add(message);
        }
        return result;
    }

    protected void processHandshake(Message handshake)
    {
        Boolean successfulField = (Boolean)handshake.get(Message.SUCCESSFUL_FIELD);
        boolean successful = successfulField != null && successfulField;

        if (successful)
        {
            // Renegotiate transport
            ClientTransport newTransport = _bayeux._transports.negotiate((String[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD), BayeuxClientImpl.BAYEUX_VERSION);
            if (newTransport == null)
            {
                // TODO: notify and stop
                throw new UnsupportedOperationException();
            }
            else if (newTransport != _transport)
            {
                _transport = lifecycleTransport(_transport, newTransport);
            }

            updateState(State.CONNECTED);
            _clientId = handshake.getClientId();

            // TODO: internal batch ?

            followAdvice();
        }
        else
        {

        }
    }

    private ClientTransport lifecycleTransport(ClientTransport oldTransport, ClientTransport newTransport)
    {
        if (oldTransport != null)
        {
            oldTransport.removeListener(_transportListener);
            oldTransport.destroy();
        }
        newTransport.addListener(_transportListener);
        newTransport.init(_bayeux);
        return newTransport;
    }
    
    protected void processConnect(Message connect)
    {
//        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.CONNECT), connect);
//        followAdvice();
    }

    protected void processMessage(Message message)
    {
//        channels.notifySubscribers(getMutableChannel(message.getChannelName()), message);
    }

    protected void processDisconnect(Message disconnect)
    {
//        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.DISCONNECT), disconnect);
    }

    protected void processUnsuccessful(Message message)
    {
        // TODO
    }

    private void followAdvice()
    {
        Map<String, Object> advice = this._advice;
        if (advice != null)
        {
            String action = (String)advice.get(Message.RECONNECT_FIELD);
            if (Message.RECONNECT_RETRY_VALUE.equals(action))
            {
                // Must connect, follow timings in the advice
                Number intervalNumber = (Number)advice.get(Message.INTERVAL_FIELD);
                if (intervalNumber != null)
                {
                    long interval = intervalNumber.longValue();
                    if (interval < 0L)
                        interval = 0L;
                    _task = _bayeux._scheduler.schedule(new Runnable()
                    {
                        public void run()
                        {
                            asyncConnect();
                        }
                    }, interval, TimeUnit.MILLISECONDS);
                }
            }
            else if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
            {
                // TODO:
                throw new UnsupportedOperationException();
            }
            else if (Message.RECONNECT_NONE_VALUE.equals(action))
            {
                // Do nothing
                // TODO: sure there is nothing more to do ?
            }
            else
            {
                Log.info("Reconnect action {} not supported in advice {}", action, advice);
            }
        }
    }

    private String newMessageId()
    {
        return String.valueOf(_messageIds.incrementAndGet());
    }
    
    private void asyncConnect()
    {
        Log.debug("Connecting with transport {}", _transport);
        Message.Mutable request = newMessage();
        request.setId(newMessageId());
        request.setClientId(_clientId);
        request.setChannelId(Channel.META_CONNECT);
        request.put(Message.CONNECTION_TYPE_FIELD, _transport.getName());
        send(request);
    }

    protected class ClientSessionChannel implements SessionChannel
    {
        private final ChannelId _id;
        private CopyOnWriteArrayList<MessageListener> _subscriptions = new CopyOnWriteArrayList<MessageListener>();
        
        protected ClientSessionChannel(String channelId)
        {
            _id=new ChannelId(channelId);
        }

        @Override
        public ClientSession getSession()
        {
            return ClientSessionImpl.this;
        }

        @Override
        public void publish(Object data)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            Message.Mutable message = newMessage();
            message.setChannelId(_id.toString());
            message.setClientId(_clientId);
            message.setData(data);
            
            send(message);
        }

        @Override
        public void subscribe(MessageListener listener)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            _subscriptions.add(listener);
            if (_subscriptions.size()==1)
            {
                Message.Mutable message = newMessage();
                message.setChannelId(Channel.META_SUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(_clientId);
                send(message);
            }
        }

        @Override
        public void unsubscribe(MessageListener listener)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            if (_subscriptions.remove(listener) && _subscriptions.size()==0)
            {
                Message.Mutable message = newMessage();
                message.setChannelId(Channel.META_UNSUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(_clientId);

                send(message);
            }
        }


        @Override
        public void unsubscribe()
        {
            // TODO Auto-generated method stub
            
        }

        @Override
        public String getId()
        {
            return _id.toString();
        }
        
        public ChannelId getChannelId()
        {
            return _id;
        }

        @Override
        public boolean isDeepWild()
        {
            return _id.isDeepWild();
        }

        @Override
        public boolean isMeta()
        {
            return _id.isMeta();
        }

        @Override
        public boolean isService()
        {
            return _id.isService();
        }

        @Override
        public boolean isWild()
        {
            return _id.isWild();
        }

    }
    
    private class Listener extends AbstractTransportListener
    {
        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            receive(messages);
        }
    }

}