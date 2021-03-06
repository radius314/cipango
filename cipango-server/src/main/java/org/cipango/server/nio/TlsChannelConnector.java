package org.cipango.server.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;

import org.cipango.server.SipConnection;
import org.cipango.server.SipServer;
import org.cipango.server.Transport;
import org.cipango.server.servlet.DefaultServlet;
import org.cipango.server.sipapp.SipAppContext;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;

public class TlsChannelConnector extends SelectChannelConnector
{
	private static final Logger LOG = Log.getLogger(TlsChannelConnector.class);
	private final SslContextFactory _sslContextFactory;
	private final List<String> _pendingClientConnections;

	public TlsChannelConnector(
            @Name("sipServer") SipServer server,
            @Name("sslContextFactory") SslContextFactory factory)
	{
		this(server, 
				factory, 
				Math.max(1,(Runtime.getRuntime().availableProcessors())/4),
        		Math.max(1,(Runtime.getRuntime().availableProcessors())/4));
	}
	

	public TlsChannelConnector(
            @Name("sipServer") SipServer server,
            @Name("sslContextFactory") SslContextFactory factory,
            @Name("acceptors") int acceptors,
            @Name("selectors") int selectors)
	{
		this(server, factory, null, null, null, acceptors, selectors);
	}
	
	public TlsChannelConnector(
	            @Name("sipServer") SipServer server,
	            @Name("sslContextFactory") SslContextFactory factory,
	            @Name("executor") Executor executor,
	            @Name("scheduler") Scheduler scheduler,
	            @Name("bufferPool") ByteBufferPool pool,
	            @Name("acceptors") int acceptors,
	            @Name("selectors") int selectors)
    {
		super(server, executor, scheduler, pool, acceptors, selectors);
		_sslContextFactory = factory;
		_pendingClientConnections = new ArrayList<String>();
    }
	
	@Override
	public Transport getTransport()
	{
		return Transport.TLS;
	}
	
	@Override
	protected void doStart() throws Exception
	{
		super.doStart();
		
		_sslContextFactory.start();
		SSLEngine engine = _sslContextFactory.newSSLEngine();
		engine.setUseClientMode(false);
//		SSLSession session = engine.getSession();
//
//		if (session.getPacketBufferSize() > getInputBufferSize())
//			setInputBufferSize(session.getPacketBufferSize());
	}
	
	@Override
	protected void doStop() throws Exception
	{
		_sslContextFactory.stop();
		super.doStop();
	}


	@Override
    protected SipConnection newConnection(InetAddress address, int port) throws IOException
    {
		_pendingClientConnections.add(getKey(address, port));
		return super.newConnection(address, port);
    }
    
	@Override
	protected Connection newConnection(EndPoint endpoint)
    {
		SSLEngine engine = _sslContextFactory.newSSLEngine(endpoint.getRemoteAddress());
		InetSocketAddress remote = endpoint.getRemoteAddress();
		boolean client = _pendingClientConnections.remove(getKey(remote.getAddress(), remote.getPort()));
		engine.setUseClientMode(client);

		TlsChannelConnection sslConnection = newSslConnection(endpoint, engine);
		//configure(sslConnection, TlsChannelConnector.this, endPoint);

		EndPoint decryptedEndPoint = sslConnection.getDecryptedEndPoint();
		Connection connection = super.newConnection(decryptedEndPoint);
		decryptedEndPoint.setConnection(connection);

		return sslConnection;
    }

	protected TlsChannelConnection newSslConnection(EndPoint endPoint, SSLEngine engine)
	{
		return new TlsChannelConnection(getByteBufferPool(), getExecutor(), endPoint, engine, this);
	}

	public static void main(String[] args) throws Exception 
	{

		String host = null;
		try
		{
			host = InetAddress.getLocalHost().getHostAddress();
		}
		catch (Exception e)
		{
			LOG.ignore(e);
			host = "127.0.0.1";
		}
		
		SslContextFactory factory = new SslContextFactory();
		factory.setKeyStorePath("C:\\projects\\cipango\\cipango-distribution\\target\\distribution\\etc\\keystore");
		factory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
		factory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
		//factory.setIncludeCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_RSA_WITH_3DES_EDE_CBC_SHA");

		SipServer sipServer = new SipServer(new QueuedThreadPool(20, 5));
		
		QueuedThreadPool threadPool = new QueuedThreadPool(20, 5);
		threadPool.setName("tp-TLS");
		
		TlsChannelConnector connector = new TlsChannelConnector(sipServer, factory, threadPool, null, null, 4, 4);

		connector.setHost(host);
		connector.setPort(5061);
		
		sipServer.addConnector(connector);
		
		SipAppContext context = new SipAppContext();
		context.getServletHandler().addServlet(DefaultServlet.class.getName());
		
		sipServer.setHandler(context);
		
		sipServer.start();
	}
}
