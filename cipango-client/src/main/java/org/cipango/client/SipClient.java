// ========================================================================
// Copyright 2011 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cipango.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.cipango.server.AbstractSipConnector;
import org.cipango.server.SipServer;
import org.cipango.server.nio.SelectChannelConnector;
import org.cipango.server.nio.UdpConnector;
import org.cipango.server.servlet.SipServletHolder;
import org.cipango.server.sipapp.SipAppContext;
import org.cipango.sip.AddressImpl;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SipClient extends AbstractLifeCycle
{
	private Logger LOG = Log.getLogger(SipClient.class);
	
	private SipServer _server;
	private SipAppContext _context;
	private List<UserAgent> _userAgents;

	public enum Protocol
	{
		TCP,
		UDP;
	}
	
	/**
	 * Creates a new client with no connector.
	 * 
	 */
	public SipClient()
	{
		_userAgents = new ArrayList<UserAgent>();
		_server = new SipServer();
		
		_context = new SipAppContext();
		_context.setName(SipClient.class.getName());
		
		SipServletHolder holder = new SipServletHolder();
		holder.setServlet(new ClientServlet());
		holder.setName(ClientServlet.class.getName());
		
		_context.getServletHandler().addServlet(holder);
		_context.getServletHandler().setMainServletName(ClientServlet.class.getName());
		
		_server.setHandler(_context);
	}
	
	public SipClient(String host, int port)
	{
		this();
		try
		{
			addConnector(Protocol.UDP, host, port);
		}
		catch(Exception e)
		{
			// That shall not happen.
			e.printStackTrace();
		}
	}

	public SipClient(int port)
	{
		this(null, port);
	}

	@Override
	protected void doStart() throws Exception
	{
		_server.start();
	}
	
	@Override
	protected void doStop() throws Exception
	{
		_server.stop();
	}

	public SipFactory getFactory()
	{
		return _context.getSipFactory();
	}
	
	public SipURI getContact()
	{
		return _server.getConnectors()[0].getURI();
	}
	
	public UserAgent createUserAgent(String aor)
	{
		UserAgent agent = new UserAgent(new AddressImpl(aor));
		addUserAgent(agent);
		return agent;
	}
	
	public void addConnector(Protocol protocol, String host, int port) throws Exception
	{
		if (_server == null || _server.isStarting() || _server.isStopping())
			throw new IllegalStateException();

		AbstractSipConnector connector = null;
		switch(protocol)
		{
		case TCP:
			connector = new SelectChannelConnector(_server);
			if (_server.getConnectors() == null || _server.getConnectors().length == 0)
				connector.setTransportParamForced(true);
			break;
		case UDP:
			connector = new UdpConnector(_server);
			break;
		}
		
		connector.setHost(host);
		connector.setPort(port);
		
        _server.addConnector(connector);
        if (_server.isStarted())
        	connector.start();
	}

	public void addUserAgent(UserAgent agent)
	{
		SipURI contact = (SipURI) getContact().clone();
		
		agent.setFactory(_context.getSipFactory());
		agent.setContact(new AddressImpl(contact));
		
		synchronized(_userAgents)
		{
			_userAgents.add(agent);
		}
	}
	
	public UserAgent getUserAgent(URI uri)
	{
		synchronized (_userAgents)
		{
			for (UserAgent agent : _userAgents)
			{
				if (agent.getAor().getURI().equals(uri))
					return agent;
			}
		}
		return null;
	}
	
	@SuppressWarnings("serial")
	class ClientServlet extends SipServlet
	{
		protected MessageHandler getHandler(SipServletResponse response)
		{
			MessageHandler handler =  (MessageHandler) response.getRequest().getAttribute(MessageHandler.class.getName());
			if (handler == null)
				return (MessageHandler) response.getSession().getAttribute(MessageHandler.class.getName());
			return handler;
		}
		
		protected MessageHandler getHandler(SipServletRequest message)
		{
			return (MessageHandler) message.getSession().getAttribute(MessageHandler.class.getName());
		}
		
		@Override
		protected void doRequest(SipServletRequest request) throws ServletException, IOException
		{
			MessageHandler handler = getHandler(request);
			if (handler != null)
			{
				handler.handleRequest(request);
				return;
			}
			
			if (request.isInitial())
			{
				Address local = request.getTo();
				UserAgent agent = getUserAgent(local.getURI());
			
				if (agent != null)
					agent.handleInitialRequest(request);
				else
					LOG.warn("No agent for initial request: " + request.getMethod() + " " + request.getRequestURI());
			}
			else
			{
				LOG.warn("No handler for request: " + request.getMethod() + " " + request.getRequestURI());
			}
		}
		
		@Override
		protected void doResponse(SipServletResponse response) throws ServletException, IOException
		{
			MessageHandler handler = getHandler(response);
			SipServletRequest request = response.getRequest();
			
			@SuppressWarnings("unchecked")
			List<SipServletResponse> l = (List<SipServletResponse>) request.getAttribute(SipServletResponse.class.getName());
			if (l==null)
			{
				l = new ArrayList<SipServletResponse>();
				request.setAttribute(SipServletResponse.class.getName(), l);
			}
			l.add(response);

			if (handler != null)
			{
				boolean process = true;
				
				if (response.getStatus() == SipServletResponse.SC_UNAUTHORIZED
						|| response.getStatus() == SipServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED)
				{
					if (handler instanceof ChallengedMessageHandler)
						process = ((ChallengedMessageHandler) handler).handleAuthentication(response);
				}

				if (process)
					handler.handleResponse(response);
			}
			else
				LOG.warn("No handler for response: " + response.getStatus() + " " + response.getMethod());
		}
	}
}
