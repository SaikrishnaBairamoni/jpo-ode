package us.dot.its.jpo.ode.vsdm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.oss.asn1.AbstractData;
import com.oss.asn1.Coder;
import com.oss.asn1.EncodeFailedException;
import com.oss.asn1.EncodeNotSupportedException;

import us.dot.its.jpo.ode.OdeProperties;
import us.dot.its.jpo.ode.asn1.j2735.CVSampleMessageBuilder;
import us.dot.its.jpo.ode.asn1.j2735.CVTypeHelper;
import us.dot.its.jpo.ode.asn1.j2735.J2735Util;
import us.dot.its.jpo.ode.j2735.J2735;
import us.dot.its.jpo.ode.j2735.semi.ServiceRequest;
import us.dot.its.jpo.ode.j2735.semi.ServiceResponse;
import us.dot.its.jpo.ode.j2735.semi.VehSitDataMessage;
import us.dot.its.jpo.ode.j2735.semi.VsmType;

public class VsdmDepositor implements Runnable {
	private String sdcIp;
	private int sdcPort;
	private int serviceRequestSenderPort;
	private int vsdmSenderPort;
	
	// The ip and port where the SDC will send the ServiceResponse back
	private String returnIp;
	private int returnPort;

	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private OdeProperties odeProps;
	
	public VsdmDepositor(String sdcIp, int sdcPort, String returnIp, int returnPort, int serviceRequestSenderPort, int vsdmSenderPort){
		this.sdcIp = sdcIp;
		this.sdcPort = sdcPort;
		this.serviceRequestSenderPort = serviceRequestSenderPort;
		this.vsdmSenderPort = vsdmSenderPort;
		this.returnIp = returnIp;
		this.returnPort = returnPort;
	}
	
	@Autowired
	public VsdmDepositor(OdeProperties odeProps){
		this(odeProps.getSdcIp(), odeProps.getSdcPort(), odeProps.getReturnIp(), odeProps.getReturnPort(), odeProps.getServiceRequestSenderPort(), odeProps.getVsdmSenderPort());
		this.odeProps = odeProps;
	}

	@Override
	public void run() {
		logger.info("ODE: Creating VsdmSender Thread");
		VsdmSender vsdmSender = new VsdmSender(sdcIp, sdcPort, vsdmSenderPort);
		Thread vsdmSenderThread = new Thread(vsdmSender, "VsdmSenderThread");
		vsdmSenderThread.start();

		logger.info("ODE: Creating ServiceRequestSender Thread");
		ServiceRequestSender serviceRequestSender = new ServiceRequestSender(sdcIp, sdcPort, serviceRequestSenderPort, returnIp, returnPort);
		Thread serviceRequestSenderThread = new Thread(serviceRequestSender, "ServiceRequestSenderThread");
		serviceRequestSenderThread.start();

		try {
			vsdmSenderThread.join();
			serviceRequestSenderThread.join();
		} catch (InterruptedException e) {
			logger.error("ODE: Interrupted Exception", e);
			Thread.currentThread().interrupt();
		}
	}
}

class ServiceRequestSender implements Runnable {
	private static Coder coder = J2735.getPERUnalignedCoder();
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	private DatagramSocket socket = null;
	private String targetHost;
	private int targetPort;
	private int selfPort;
	private String returnIp;
	private int returnPort;

	public ServiceRequestSender(String targetHost, int targetPort, int selfPort, String returnIp, int returnPort) {
		this.targetHost = targetHost;
		this.targetPort = targetPort;
		this.selfPort = selfPort;
		this.returnIp = returnIp;
		this.returnPort = returnPort;
		try {
			socket = new DatagramSocket(this.selfPort);
			logger.info("ODE: Created ServiceRequestSender Socket with port " + this.selfPort);
		} catch (SocketException e) {
			logger.error("ODE: Error creating socket with port " + this.selfPort, e);
		}
	}

	private void sendVsdServiceRequest() {
		ServiceRequest sr = CVSampleMessageBuilder.buildVehicleSituationDataServiceRequest(returnIp, returnPort);
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		try {
			coder.encode(sr, sink);
			byte[] payload = sink.toByteArray();
			logger.info("ODE: Sending VSD Deposit ServiceRequest ...");
			socket.send(new DatagramPacket(payload, payload.length, new InetSocketAddress(targetHost, targetPort)));
		} catch (EncodeFailedException | EncodeNotSupportedException | IOException e) {
			logger.error("ODE: Error Sending VSD Deposit ServiceRequest", e);
		}
	}

	@Override
	public void run() {
		sendVsdServiceRequest();

		if (socket != null) {
			logger.info("Closing ServiceRequestSender Socket with port " + this.selfPort);
			socket.close();
		}
	}
}

class VsdmSender implements Runnable {
	private static final int DEFAULT_TIMEOUT = 5000;
	private static final int DEFAULT_BUFFER_LENGTH = 10000;
	private static final double DEFAULT_LAT = 43.394444; //Wyoming lat/lon
	private static final double DEFAULT_LON = -107.595;
	private static Coder coder = J2735.getPERUnalignedCoder();
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	private DatagramSocket socket = null;
	private String targetHost;
	private int targetPort;
	private int selfPort;

	public VsdmSender(String targetHost, int targetPort, int selfPort) {
		this.setTargetHost(targetHost);
		this.setTargetPort(targetPort);
		this.setSelfPort(selfPort);
		try {
			socket = new DatagramSocket(selfPort);
			logger.info("ODE: Created VsdmSender Socket with port " + this.selfPort);
			socket.setSoTimeout(DEFAULT_TIMEOUT);
		} catch (SocketException e) {
			logger.error("ODE: Error creating socket with port " + this.selfPort, e);
		}
	}

	public String getTargetHost() {
		return targetHost;
	}

	public void setTargetHost(String targetHost) {
		this.targetHost = targetHost;
	}

	public int getTargetPort() {
		return targetPort;
	}

	public void setTargetPort(int targetPort) {
		this.targetPort = targetPort;
	}

	public int getSourcePort() {
		return selfPort;
	}

	public void setSelfPort(int sourcePort) {
		this.selfPort = sourcePort;
	}

	@Override
	public void run() {
		logger.info("ODE: Initializing VSD deposit to SDC ...");
		if(receiveVsdServiceResponse())
			sendVsdMessage();
		else
			logger.info("ODE: Vsd message was not sent because a valid Service Response was not received.");

		if (socket != null) {
			logger.info("ODE: Closing VsdmSender Socket with port " + this.selfPort);
			socket.close();
		}
	}

	private boolean receiveVsdServiceResponse() {
		try {
			byte[] buffer = new byte[DEFAULT_BUFFER_LENGTH];
			logger.info("ODE: Waiting for VSD deposit ServiceResponse...");
			DatagramPacket responeDp = new DatagramPacket(buffer, buffer.length);
			socket.receive(responeDp);

			logger.info("ODE: Received VSD Deposit ServiceResponse");
			if (buffer.length > 0) {
				AbstractData response = J2735Util.decode(coder, buffer);
				if (response instanceof ServiceResponse) {
					logger.info("ODE: Printing VSD Deposit ServiceResponse {}", response.toString());
					return true;
				}
			}
		} catch (Exception e) {
			logger.error("ODE: Error Receiving VSD Deposit ServiceResponse", e);
		}
		return false;
	}

	private void sendVsdMessage() {
		logger.info("ODE: Preparing VSD message deposit...");
		VehSitDataMessage vsdm;
		try {
			vsdm = CVSampleMessageBuilder.buildVehSitDataMessage(DEFAULT_LAT, DEFAULT_LON);
			VsmType vsmType = new VsmType(CVTypeHelper.VsmType.VEHSTAT.arrayValue());
			vsdm.setType(vsmType);
			byte[] encodedMsg = CVSampleMessageBuilder.messageToEncodedBytes(vsdm);
			logger.info("ODE: Sending VSD message to SDC...");
			socket.send(
					new DatagramPacket(encodedMsg, encodedMsg.length, new InetSocketAddress(targetHost, targetPort)));
			logger.info("ODE: Sent VSD message to SDC");
		} catch (EncodeFailedException | EncodeNotSupportedException | IOException e) {
			logger.error("ODE: Error Sending VSD Message", e);
		}
	}
}
