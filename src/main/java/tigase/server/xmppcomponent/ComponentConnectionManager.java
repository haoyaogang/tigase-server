/*
 *  Package Tigase XMPP/Jabber Server
 *  Copyright (C) 2004, 2005, 2006
 *  "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software Foundation,
 *  Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.server.xmppcomponent;

import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import tigase.net.ConnectionType;
import tigase.net.IOService;
import tigase.net.SocketType;
import tigase.server.ConnectionManager;
import tigase.server.MessageReceiver;
import tigase.server.Packet;
import tigase.server.MessageRouter;
import tigase.disco.XMPPService;
import tigase.disco.ServiceEntity;
import tigase.disco.ServiceIdentity;
import tigase.util.Algorithms;
import tigase.util.JID;
import tigase.xml.Element;
import tigase.xmpp.XMPPIOService;

/**
 * Class ComponentConnectionManager
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class ComponentConnectionManager extends ConnectionManager
	implements XMPPService {

	public static int[] PORTS = {5555};
	public static ConnectionType PORT_TYPE_PROP_VAL = ConnectionType.accept;
	public static SocketType PORT_SOCKET_PROP_VAL = SocketType.plain;
  public static final String SECRET_PROP_KEY = "secret";
  public static String SECRET_PROP_VAL =	"someSecret";
	public static final String PORT_LOCAL_HOST_PROP_KEY = "local-host";
	public static String PORT_LOCAL_HOST_PROP_VAL = "comp_1.localhost";
	public static String PORT_REMOTE_HOST_PROP_VAL = "localhost";
	public static final String PORT_ROUTING_TABLE_PROP_KEY = "routing-table";
	public static String[] PORT_ROUTING_TABLE_PROP_VAL =
	{ PORT_REMOTE_HOST_PROP_VAL };
	public static String[] PORT_IFC_PROP_VAL = {"*"};
	public static final String PACK_ROUTED_KEY = "pack-routed";
	public static boolean PACK_ROUTED_VAL = false;
	public static final String RETURN_SERVICE_DISCO_KEY = "service-disco";
	public static final boolean RETURN_SERVICE_DISCO_VAL = true;
	public static final String IDENTITY_TYPE_KEY = "identity-type";
	public static final String IDENTITY_TYPE_VAL = "generic";

	private ServiceEntity serviceEntity = null;
	private boolean pack_routed = PACK_ROUTED_VAL;
	private boolean service_disco = RETURN_SERVICE_DISCO_VAL;
	private String identity_type = IDENTITY_TYPE_VAL;
	private String remote_host = null;

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger("tigase.server.xmppcomponent.ComponentConnectionManager");

	public void processPacket(Packet packet) {
		log.finer("Processing packet: " + packet.getElemName()
			+ ", type: " + packet.getType());
		log.finest("Processing packet: " + packet.getStringData());
		if (pack_routed) {
			writePacketToSocket(packet.packRouted());
		} else {
			writePacketToSocket(packet);
		}
	}

	public Queue<Packet> processSocketData(XMPPIOService serv) {
		Packet p = null;
		while ((p = serv.getReceivedPackets().poll()) != null) {
			log.finer("Processing packet: " + p.getElemName()
				+ ", type: " + p.getType());
			log.finest("Processing socket data: " + p.getStringData());
			if (p.getElemName().equals("handshake")) {
				processHandshake(p, serv);
			} else {
				if (p.isRouted()) {
					p = p.unpackRouted();
				} // end of if (p.isRouted())
				addOutPacket(p);
			}
		} // end of while ()
		return null;
	}

	private void processHandshake(Packet p, XMPPIOService serv) {
		switch (serv.connectionType()) {
		case connect: {
			String data = p.getElemCData();
			if (data == null) {
				String[] routings =
					(String[])serv.getSessionData().get(PORT_ROUTING_TABLE_PROP_KEY);
				updateRoutings(routings, true);
				String addr =
					(String)serv.getSessionData().get(PORT_REMOTE_HOST_PROP_KEY);
				log.fine("Connected to: " + addr);
				updateServiceDiscovery(addr, "XEP-0114 connected");
			} else {
				log.warning("Incorrect packet received: " + p.getStringData());
			}
			break;
		}
		case accept: {
			String digest = p.getElemCData();
			String id =
				(String)serv.getSessionData().get(serv.SESSION_ID_KEY);
			String secret =
				(String)serv.getSessionData().get(SECRET_PROP_KEY);
			try {
				String loc_digest = Algorithms.hexDigest(id, secret, "SHA");
				log.finest("Calculating digest: id="+id+", secret="+secret
					+", digest="+loc_digest);
				if (digest != null && digest.equals(loc_digest)) {
					Packet resp = new Packet(new Element("handshake"));
					writePacketToSocket(serv, resp);
					String[] routings =
						(String[])serv.getSessionData().get(PORT_ROUTING_TABLE_PROP_KEY);
					updateRoutings(routings, true);
					String addr = (String)serv.getSessionData().get(serv.HOSTNAME_KEY);
					log.fine("Connected to: " + addr);
					updateServiceDiscovery(addr, "XEP-0114 connected");
				} else {
					log.info("Handshaking passwords don't match, disconnecting...");
					serv.stop();
				}
			} catch (Exception e) {
				log.log(Level.SEVERE, "Handshaking error.", e);
			}
			break;
		}
		default:
			// Do nothing, more data should come soon...
			break;
		} // end of switch (service.connectionType())
	}

	public Map<String, Object> getDefaults(Map<String, Object> params) {
		String config_type = (String)params.get("config-type");
		if (config_type.equals("--gen-config-sm")) {
			PACK_ROUTED_VAL = true;
			PORT_TYPE_PROP_VAL = ConnectionType.accept;
		}
		if (config_type.equals("--gen-config-cs")) {
			PACK_ROUTED_VAL = true;
			PORT_TYPE_PROP_VAL = ConnectionType.connect;
			PORT_IFC_PROP_VAL = new String[] {"localhost"};
		}
		if (params.get("--ext-comp") != null) {
			String[] comp_params = ((String)params.get("--ext-comp")).split(",");
			int idx = 0;
			if (comp_params.length >= idx + 1) {
				PORT_LOCAL_HOST_PROP_VAL = comp_params[idx++];
			}
			if (comp_params.length >= idx + 1) {
				PORT_REMOTE_HOST_PROP_VAL = comp_params[idx++];
				PORT_ROUTING_TABLE_PROP_VAL = new String[] { PORT_REMOTE_HOST_PROP_VAL };
				if (config_type.equals("--gen-config-cs")) {
					PORT_IFC_PROP_VAL = new String[] {PORT_REMOTE_HOST_PROP_VAL};
				}
			}
			if (comp_params.length >= idx + 1) {
				try {
					PORTS[0] = Integer.decode(comp_params[idx++]);
				} catch (NumberFormatException e) {
					log.warning("Incorrect component port number: " + comp_params[idx-1]);
					PORTS[0] = 5555;
				}
			}
			if (comp_params.length >= idx + 1) {
				SECRET_PROP_VAL = comp_params[idx++];
			}
			if (comp_params.length >= idx + 1) {
				if (comp_params[idx++].equals("ssl")) {
					PORT_SOCKET_PROP_VAL = SocketType.plain;
				}
			}
			if (comp_params.length >= idx + 1) {
				if (comp_params[idx++].equals("accept")) {
					PORT_TYPE_PROP_VAL = ConnectionType.accept;
				} else {
					PORT_TYPE_PROP_VAL = ConnectionType.connect;
				}
			}
			if (comp_params.length >= idx + 1) {
				PORT_ROUTING_TABLE_PROP_VAL = new String[] { comp_params[idx++] };
			}
		}
		Map<String, Object> props = super.getDefaults(params);
		props.put(PACK_ROUTED_KEY, PACK_ROUTED_VAL);
		props.put(RETURN_SERVICE_DISCO_KEY, RETURN_SERVICE_DISCO_VAL);
		props.put(IDENTITY_TYPE_KEY, IDENTITY_TYPE_VAL);
		return props;
	}

	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);
		pack_routed = (Boolean)props.get(PACK_ROUTED_KEY);
		service_disco = (Boolean)props.get(RETURN_SERVICE_DISCO_KEY);
		identity_type = (String)props.get(IDENTITY_TYPE_KEY);

		//serviceEntity = new ServiceEntity(getName(), "external", "XEP-0114");
		serviceEntity = new ServiceEntity("XEP-0114 " + getName(), null, "XEP-0114");
		serviceEntity.addIdentities(
			new ServiceIdentity("component", identity_type, getName()));
	}

	protected int[] getDefPlainPorts() {
		return PORTS;
	}

	protected Map<String, Object> getParamsForPort(int port) {
    Map<String, Object> defs = new TreeMap<String, Object>();
		defs.put(SECRET_PROP_KEY, SECRET_PROP_VAL);
		defs.put(PORT_LOCAL_HOST_PROP_KEY, PORT_LOCAL_HOST_PROP_VAL);
		defs.put(PORT_ROUTING_TABLE_PROP_KEY, PORT_ROUTING_TABLE_PROP_VAL);
		defs.put(PORT_TYPE_PROP_KEY, PORT_TYPE_PROP_VAL);
		defs.put(PORT_SOCKET_PROP_KEY, PORT_SOCKET_PROP_VAL);
		defs.put(PORT_REMOTE_HOST_PROP_KEY, PORT_REMOTE_HOST_PROP_VAL);
		defs.put(PORT_IFC_PROP_KEY, PORT_IFC_PROP_VAL);
		defs.put(MAX_RECONNECTS_PROP_KEY, new Long(30*MINUTE).intValue());
		return defs;
	}

	protected String getUniqueId(IOService serv) {
		remote_host =
			(String)serv.getSessionData().get(PORT_REMOTE_HOST_PROP_KEY);
		return remote_host;
		// 		return serv.getRemoteHost();
	}

	public void serviceStopped(final IOService service) {
		super.serviceStopped(service);
		Map<String, Object> sessionData = service.getSessionData();
		String[] routings = (String[])sessionData.get(PORT_ROUTING_TABLE_PROP_KEY);
		updateRoutings(routings, false);
		ConnectionType type = service.connectionType();
		if (type == ConnectionType.connect) {
			reconnectService(sessionData, connectionDelay);
		} // end of if (type == ConnectionType.connect)
		//		removeRouting(serv.getRemoteHost());
		String addr = (String)sessionData.get(PORT_REMOTE_HOST_PROP_KEY);
		log.fine("Disonnected from: " + addr);
		updateServiceDiscovery(addr, "XEP-0114 disconnected");
	}

	protected String getServiceId(Packet packet) {
		return JID.getNodeHost(remote_host);
	}

	public void serviceStarted(final IOService service) {
		super.serviceStarted(service);
		log.finest("c2c connection opened: " + service.getRemoteAddress()
			+ ", type: " + service.connectionType().toString()
			+ ", id=" + service.getUniqueId());
// 		String addr =
// 			(String)service.getSessionData().get(PORT_REMOTE_HOST_PROP_KEY);
// 		addRouting(addr);
		//		addRouting(serv.getRemoteHost());
		switch (service.connectionType()) {
		case connect:
			// Send init xmpp stream here
			XMPPIOService serv = (XMPPIOService)service;
			String compName =
				(String)service.getSessionData().get(PORT_LOCAL_HOST_PROP_KEY);
			String data =
				"<stream:stream"
				+ " xmlns='jabber:component:accept'"
				+ " xmlns:stream='http://etherx.jabber.org/streams'"
				+ " to='" + compName + "'"
				+ ">";
			log.finest("cid: " + (String)serv.getSessionData().get("cid")
				+ ", sending: " + data);
			serv.xmppStreamOpen(data);
			break;
		default:
			// Do nothing, more data should come soon...
			break;
		} // end of switch (service.connectionType())
	}

	public String xmppStreamOpened(XMPPIOService service,
		Map<String, String> attribs) {

		log.finer("Stream opened: " + attribs.toString());

		switch (service.connectionType()) {
		case connect: {
			String id = attribs.get("id");
			service.getSessionData().put(service.SESSION_ID_KEY, id);
			String secret =
				(String)service.getSessionData().get(SECRET_PROP_KEY);
			try {
				String digest = Algorithms.hexDigest(id, secret, "SHA");
				log.finest("Calculating digest: id="+id+", secret="+secret
					+", digest="+digest);
				return "<handshake>" + digest + "</handshake>";
			} catch (NoSuchAlgorithmException e) {
				log.log(Level.SEVERE, "Can not generate digest for pass phrase.", e);
				return null;
			}
		}
		case accept: {
			String hostname = attribs.get("to");
			service.getSessionData().put(service.HOSTNAME_KEY, hostname);
			String id = UUID.randomUUID().toString();
			service.getSessionData().put(service.SESSION_ID_KEY, id);
			return "<stream:stream"
				+ " xmlns='jabber:component:accept'"
				+ " xmlns:stream='http://etherx.jabber.org/streams'"
				+ " from='" + hostname + "'"
				+ " id='" + id + "'"
				+ ">";
		}
		default:
			// Do nothing, more data should come soon...
			break;
		} // end of switch (service.connectionType())
		return null;
	}

	public void xmppStreamClosed(XMPPIOService serv) {
		log.finer("Stream closed.");
	}

	/**
	 * Method <code>getMaxInactiveTime</code> returns max keep-alive time
	 * for inactive connection. we shoulnd not really close external component
	 * connection at all, so let's say something like: 1000 days...
	 *
	 * @return a <code>long</code> value
	 */
	protected long getMaxInactiveTime() {
		return 1000*24*HOUR;
	}

	private void updateRoutings(String[] routings, boolean add) {
		if (add) {
			for (String route: routings) {
				try {
					addRegexRouting(route);
				} catch (Exception e) {
					addRouting(route);
				}
			}
		} else {
			for (String route: routings) {
				try {
					removeRegexRouting(route);
				} catch (Exception e) {
					removeRouting(route);
				}
			}
		}
	}

	private void updateServiceDiscovery(String jid, String name) {
		ServiceEntity item = new ServiceEntity(jid, null, name);
		//item.addIdentities(new ServiceIdentity("component", identity_type, name));
		log.finest("Modifing service-discovery info: " + item.toString());
		serviceEntity.addItems(item);
	}

	public Element getDiscoInfo(String node, String jid) {
		if (jid != null && jid.startsWith(getName()+".")) {
			return serviceEntity.getDiscoInfo(node);
		}
		return null;
	}

	public List<Element> getDiscoItems(String node, String jid) {
		if (jid.startsWith(getName()+".")) {
			return serviceEntity.getDiscoItems(node, null);
		} else {
 			return Arrays.asList(serviceEntity.getDiscoItem(null, getName() + "." + jid));
		}
	}

}
