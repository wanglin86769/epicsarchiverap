/*******************************************************************************
 * Copyright (c) 2011 The Board of Trustees of the Leland Stanford Junior University
 * as Operator of the SLAC National Accelerator Laboratory.
 * Copyright (c) 2011 Brookhaven National Laboratory.
 * EPICS archiver appliance is distributed subject to a Software License Agreement found
 * in file LICENSE that is included with this distribution.
 *******************************************************************************/
package org.epics.archiverappliance.mgmt.bpl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.epics.archiverappliance.common.BPLAction;
import org.epics.archiverappliance.common.TimeUtils;
import org.epics.archiverappliance.config.ArchDBRTypes;
import org.epics.archiverappliance.config.ConfigService;
import org.epics.archiverappliance.config.PVNames;
import org.epics.archiverappliance.config.PVTypeInfo;
import org.epics.archiverappliance.config.UserSpecifiedSamplingParams;
import org.epics.archiverappliance.mgmt.policy.PolicyConfig;
import org.epics.archiverappliance.mgmt.policy.PolicyConfig.SamplingMethod;
import org.epics.archiverappliance.utils.ui.MimeTypeConstants;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * BPL for archiving a PV.
 * 
 * @epics.BPLAction - Archive one or more PV's. 
 * @epics.BPLActionParam pv - The name of the pv to be archived. If archiving more than one PV, use a comma separated list. You can also send the PV list as part of the POST body using standard techniques. If you need to specify different archiving parameters for each PV, send the data as a JSON array (remember to send the content type correctly).
 * @epics.BPLActionParam samplingperiod - The sampling period to be used. Optional, default value is 1.0 seconds.
 * @epics.BPLActionParam samplingmethod - The sampling method to be used. For now, this is one of SCAN or MONITOR. Optional, default value is MONITOR.
 * @epics.BPLActionParam controllingPV - The controlling PV for coditional archiving. Optional; if unspecified, we do not use conditional archiving.
 * @epics.BPLActionParam policy - Override the policy execution process and use this policy instead. Optional; if unspecified, we go thru the normal policy execution process.
 * @epics.BPLActionEnd
 * 
 * @author mshankar
 *
 */
public class ArchivePVAction implements BPLAction {
	public static final Logger logger = Logger.getLogger(ArchivePVAction.class);

	@Override
	public void execute(HttpServletRequest req, HttpServletResponse resp, ConfigService configService) throws IOException {
		String contentType = req.getContentType();
		if(contentType != null && contentType.equals(MimeTypeConstants.APPLICATION_JSON)) { 
			processJSONRequest(req, resp, configService);
			return;
		}
		
		logger.info("Archiving pv(s) " + req.getParameter("pv"));
		String[] pvs = req.getParameter("pv").split(",");
		String samplingPeriodStr = req.getParameter("samplingperiod");
		boolean samplingPeriodSpecified = samplingPeriodStr != null && !samplingPeriodStr.equals("");
		float samplingPeriod = PolicyConfig.DEFAULT_MONITOR_SAMPLING_PERIOD;
		if(samplingPeriodSpecified) {
			samplingPeriod = Float.parseFloat(samplingPeriodStr);
		}
		
		String samplingMethodStr = req.getParameter("samplingmethod");
		SamplingMethod samplingMethod = SamplingMethod.MONITOR;
		if(samplingMethodStr != null) {
			samplingMethod = SamplingMethod.valueOf(samplingMethodStr);
		}
		
		String controllingPV = req.getParameter("controllingPV");
		if(controllingPV != null && !controllingPV.equals("")) {
			logger.debug("We are conditionally archiving using controlling PV " + controllingPV);
		}
		
		String policyName = req.getParameter("policy");
		if(policyName != null && !policyName.equals("")) {
			logger.info("We have a user override for policy " + policyName);
		}
		List<String> fieldsAsPartOfStream = ArchivePVAction.getFieldsAsPartOfStream(configService);
		
		if(pvs.length < 1) { return; }
		resp.setContentType(MimeTypeConstants.APPLICATION_JSON);
		try (PrintWriter out = resp.getWriter()) {
			out.println("[");
			boolean isFirst = true;
			for(String pv : pvs) {
				if(isFirst) { isFirst = false; } else { out.println(","); }
				logger.debug("Calling archivePV for pv " + pv);
				archivePV(out, pv, samplingPeriodSpecified, samplingMethod, samplingPeriod, controllingPV, policyName, null, configService, fieldsAsPartOfStream);
			}
			out.println("]");
		}
	}
	
	/**
	 * Performance optimization - pass in fieldsArchivedAsPartOfStream as part of archivePV call.
	 * @param configService
	 * @return
	 */
	public static List<String> getFieldsAsPartOfStream(ConfigService configService) { 
		List<String> fieldsArchivedAsPartOfStream = new LinkedList<String>();
		try { 
			fieldsArchivedAsPartOfStream = configService.getFieldsArchivedAsPartOfStream();
		} catch(IOException ex) {
			logger.error("Exception fetching standard fields", ex);
		}
		return fieldsArchivedAsPartOfStream;
	}
	

	/**
	 * @param out
	 * @param pvName
	 * @param overridePolicyParams
	 * @param overRiddenScan
	 * @param overRiddenSamplingPeriod
	 * @param controllingPV - The PV used for controlling whether we archive this PV or not in conditional archiving.
	 * @param policyName - If you want to override the policy on a per PV basis.
	 * @param alias - Optional, any alias that you'd like to register at the same time.
	 * @param configService
	 */
	public static void archivePV(PrintWriter out, String pvName, boolean overridePolicyParams, SamplingMethod overriddenSamplingMethod, float overRiddenSamplingPeriod, String controllingPV, String policyName, String alias, ConfigService configService, List<String> fieldsArchivedAsPartOfStream) throws IOException {
		String fieldName = PVNames.getFieldName(pvName);
		boolean isStandardFieldName = false;

		
		if(fieldName != null && !fieldName.equals("")) { 
			if (fieldName.equals("VAL")) {
				logger.debug("Treating .VAL as pv Name alone for " + pvName);
				fieldName = null;
				pvName = PVNames.stripFieldNameFromPVName(pvName);
			} else if(fieldsArchivedAsPartOfStream.contains(fieldName)) {
				logger.debug("Field " + fieldName + " is one of the standard fields for pv " + pvName);
				pvName = PVNames.stripFieldNameFromPVName(pvName);
				isStandardFieldName = true;
			}
		}
		
		
		if(!PVNames.isValidPVName(pvName)) { 
			String msg = "PV name fails syntax check " + pvName;
			logger.error(msg);
			throw new IOException(msg);
		}
		
		PVTypeInfo typeInfo = configService.getTypeInfoForPV(pvName);
		if(typeInfo != null) {
			logger.debug("We are already archiving this pv " + pvName + " and have a typeInfo");
			if(fieldName != null && !fieldName.equals("")) {
				if(isStandardFieldName) {
					logger.debug("Checking to see if field " + fieldName + " is being archived");
					if(typeInfo.checkIfFieldAlreadySepcified(fieldName)) {
						logger.debug("Field " + fieldName + " is already being archived");
					} else {
						logger.debug("Adding field " + fieldName + " to a pv that is already being archived");
						typeInfo.addArchiveField(fieldName);
						typeInfo.setModificationTime(TimeUtils.now());
						configService.updateTypeInfoForPV(pvName, typeInfo);
						// If we determine we need to kick off a pause/resume; here's where we need to do it.
					}
				}
			}
			
			if(alias != null) { 
				configService.addAlias(alias, pvName);
			}

			
			out.println("{ \"pvName\": \"" + pvName + "\", \"status\": \"Already submitted\" }");
			return;
		}
		
		boolean requestAlreadyMade = configService.doesPVHaveArchiveRequestInWorkflow(pvName);
		if(requestAlreadyMade) {
			UserSpecifiedSamplingParams userParams = configService.getUserSpecifiedSamplingParams(pvName);
			if(fieldName != null && !fieldName.equals("")) {
				if(isStandardFieldName) {
					if(userParams != null && !userParams.checkIfFieldAlreadySepcified(fieldName)) {
						logger.debug("Adding field " + fieldName + " to an existing request. Note we are not updating persistence here.");
						userParams.addArchiveField(fieldName);
					}
				}
			}
			
			if(alias != null) { 
				logger.debug("Adding alias " + alias + " to user params of " + pvName + "(1)");
				userParams.addAlias(alias);
			}


			logger.warn("We have a pending request for pv " + pvName);
			out.println("{ \"pvName\": \"" + pvName + "\", \"status\": \"Already submitted\" }");
			return;
		}
		
		try {
			String actualPVName = pvName;
			if(pvName.startsWith(ArchDBRTypes.V4PREFIX)) {
				actualPVName = pvName.substring(10);
			}

			if(overridePolicyParams) {
				String minumumSamplingPeriodStr = configService.getInstallationProperties().getProperty("org.epics.archiverappliance.mgmt.bpl.ArchivePVAction.minimumSamplingPeriod", "0.1");
				float minumumSamplingPeriod = Float.parseFloat(minumumSamplingPeriodStr);
				if(overRiddenSamplingPeriod < minumumSamplingPeriod) {
					logger.warn("Enforcing the minumum sampling period of " + minumumSamplingPeriod + " for pv " + pvName);
					overRiddenSamplingPeriod = minumumSamplingPeriod;
				}
				logger.debug("Overriding policy params with sampling method " + overriddenSamplingMethod + " and sampling period " + overRiddenSamplingPeriod);
				UserSpecifiedSamplingParams userSpecifiedSamplingParams = new UserSpecifiedSamplingParams(overridePolicyParams ? overriddenSamplingMethod : SamplingMethod.MONITOR, overRiddenSamplingPeriod, controllingPV, policyName);
				if(fieldName != null && !fieldName.equals("") && isStandardFieldName) {
					userSpecifiedSamplingParams.addArchiveField(fieldName);
				}
				
				if(alias != null) { 
					logger.debug("Adding alias " + alias + " to user params of " + actualPVName + "(2)");
					userSpecifiedSamplingParams.addAlias(alias);
				}
				
				configService.addToArchiveRequests(actualPVName, userSpecifiedSamplingParams);
			} else {
				UserSpecifiedSamplingParams userSpecifiedSamplingParams = new UserSpecifiedSamplingParams();
				if(fieldName != null && !fieldName.equals("") && isStandardFieldName) {
					userSpecifiedSamplingParams.addArchiveField(fieldName);
				}

				if(alias != null) { 
					logger.debug("Adding alias " + alias + " to user params of " + actualPVName + "(3)");
					userSpecifiedSamplingParams.addAlias(alias);
				}

				configService.addToArchiveRequests(actualPVName, userSpecifiedSamplingParams);
			}
			// Submit the request to the archive engine.
			// We have to make this a call into the engine to get over that fact that only the engine can load JCA libraries
			configService.getMgmtRuntimeState().startPVWorkflow(pvName);
			out.println("{ \"pvName\": \"" + pvName + "\", \"status\": \"Archive request submitted\" }");
		} catch(Exception ex) {
			logger.error("Exception archiving PV " + pvName, ex);
			out.println("{ \"pvName\": \"" + pvName + "\", \"status\": \"Exception occured\" }");
		}
	}
	
	
	private void processJSONRequest(HttpServletRequest req, HttpServletResponse resp, ConfigService configService) throws IOException { 
		logger.debug("Archiving multiple PVs from a JSON POST request");
		List<String> fieldsAsPartOfStream = ArchivePVAction.getFieldsAsPartOfStream(configService);
		try (LineNumberReader lineReader = new LineNumberReader(new InputStreamReader(new BufferedInputStream(req.getInputStream())))) {
			JSONParser parser=new JSONParser();
			JSONArray pvArchiveParams = (JSONArray) parser.parse(lineReader);
			logger.debug("PV count " + pvArchiveParams.size());
			resp.setContentType(MimeTypeConstants.APPLICATION_JSON);
			try(PrintWriter out = resp.getWriter()) { 			
				out.println("[");
				
				boolean isFirst = true;
				for(Object pvArchiveParamObj : pvArchiveParams) {
					logger.debug("Processing...");
					JSONObject pvArchiveParam = (JSONObject) pvArchiveParamObj;
					logger.debug("Processing...");
					if(isFirst) { isFirst = false; } else { out.println(","); }
					logger.debug("Processing...");
					String pvName = (String) pvArchiveParam.get("pv");
					logger.debug("Calling archivePV for pv " + pvName);
					ArchivePVAction.archivePV(out, 
							pvName, 
							pvArchiveParam.containsKey("samplingperiod"), 
							pvArchiveParam.containsKey("samplingmethod") ? SamplingMethod.valueOf(((String) pvArchiveParam.get("samplingmethod")).toUpperCase()) : SamplingMethod.MONITOR, 
									pvArchiveParam.containsKey("samplingperiod") ? Float.parseFloat((String)pvArchiveParam.get("samplingperiod")) : PolicyConfig.DEFAULT_MONITOR_SAMPLING_PERIOD, 
											(String) pvArchiveParam.get("controllingPV"),
											(String) pvArchiveParam.get("policy"),
											(String) pvArchiveParam.get("alias"),
											configService,
											fieldsAsPartOfStream);
				}
				
				out.println("]");
				out.flush();
			}
		} catch(Exception ex) {
			logger.error("Exception processing archiveJSON", ex);
			throw new IOException(ex);
		}
		return;
	}
}
