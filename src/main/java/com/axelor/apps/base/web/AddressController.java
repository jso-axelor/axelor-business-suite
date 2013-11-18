/**
 * Copyright (c) 2012-2013 Axelor. All Rights Reserved.
 *
 * The contents of this file are subject to the Common Public
 * Attribution License Version 1.0 (the “License”); you may not use
 * this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://license.axelor.com/.
 *
 * The License is based on the Mozilla Public License Version 1.1 but
 * Sections 14 and 15 have been added to cover use of software over a
 * computer network and provide for limited attribution for the
 * Original Developer. In addition, Exhibit A has been modified to be
 * consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an “AS IS”
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is part of "Axelor Business Suite", developed by
 * Axelor exclusively.
 *
 * The Original Developer is the Initial Developer. The Initial Developer of
 * the Original Code is Axelor.
 *
 * All portions of the code written by Axelor are
 * Copyright (c) 2012-2013 Axelor. All Rights Reserved.
 */
package com.axelor.apps.base.web;

import groovy.json.JsonBuilder;
import groovy.json.JsonOutput;
import groovy.lang.Closure;
import groovy.util.XmlSlurper;
import groovy.util.slurpersupport.GPathResult;
import groovy.util.slurpersupport.Node;
import groovy.util.slurpersupport.NodeChild;
import groovy.util.slurpersupport.NodeChildren;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import wslite.json.JSONArray;
import wslite.json.JSONException;
import wslite.json.JSONObject;
import wslite.rest.ContentType;
import wslite.rest.RESTClient;
import wslite.rest.Response;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.AddressExport;
import com.axelor.apps.base.db.General;
import com.axelor.apps.base.db.Import;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerList;
import com.axelor.apps.base.db.PickListEntry;
import com.axelor.apps.base.service.AddressService;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.base.service.user.UserInfoService;
import com.axelor.data.Importer;
import com.axelor.data.csv.CSVImporter;
import com.axelor.data.xml.XMLImporter;
import com.axelor.exception.service.TraceBackService;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.persist.Transactional;
import com.qas.web_2005_02.AddressLineType;
import com.qas.web_2005_02.PicklistEntryType;
import com.qas.web_2005_02.QAAddressType;
import com.qas.web_2005_02.QAPicklistType;
import com.qas.web_2005_02.VerifyLevelType;

public class AddressController {

	@Inject
	private Injector injector;

	@Inject
	private AddressService ads;

	@Inject 
	private UserInfoService uis;

	private static final Logger LOG = LoggerFactory.getLogger(AddressController.class);

	public void check(ActionRequest request, ActionResponse response) {

		General g = request.getContext().asType(General.class);
		LOG.debug("validate g = {}", g);
		LOG.debug("validate g.qasWsdlUrl = {}", g.getQasWsdlUrl());

		String msg = ads.check(g.getQasWsdlUrl())? g.getQasWsdlUrl()+" Ok":"Service indisponible, veuillez contacter votre adminstrateur";
		response.setFlash(msg);		
	}

	public void validate(ActionRequest request, ActionResponse response) {

		Address a = request.getContext().asType(Address.class);
		LOG.debug("validate a = {}", a);
		String search = a.getAddressL4()+" "+a.getAddressL6();
		Map<String,Object> retDict = (Map<String, Object>) ads.validate(GeneralService.getGeneral().getQasWsdlUrl(), search);
		LOG.debug("validate retDict = {}", retDict);

		VerifyLevelType verifyLevel = (VerifyLevelType) retDict.get("verifyLevel");

		if (verifyLevel != null && verifyLevel.value().equals("Verified")) {

			QAAddressType address = (QAAddressType) retDict.get("qaAddress");
			String addL1;
			List<AddressLineType> addressLineType = address.getAddressLine();
			addL1 = addressLineType.get(0).getLine();
			response.setValue("addressL2", addressLineType.get(1).getLine());
			response.setValue("addressL3", addressLineType.get(2).getLine());
			response.setValue("addressL4", addressLineType.get(3).getLine());
			response.setValue("addressL5", addressLineType.get(4).getLine());
			response.setValue("addressL6", addressLineType.get(5).getLine());
			response.setValue("inseeCode", addressLineType.get(6).getLine());
			response.setValue("certifiedOk", true);
			response.setValue("pickList", new ArrayList<QAPicklistType>());
			if (addL1 != null) {
				response.setFlash("Ligne 1: "+addL1);
			}
		} else if (verifyLevel != null && (verifyLevel.value().equals("Multiple") || verifyLevel.value().equals("StreetPartial") || verifyLevel.value().equals("InteractionRequired") || verifyLevel.value().equals("PremisesPartial"))) {
			LOG.debug("retDict.verifyLevel = {}", retDict.get("verifyLevel"));
			QAPicklistType qaPicklist =  (QAPicklistType) retDict.get("qaPicklist");
			List<PickListEntry> pickList = new ArrayList<PickListEntry>();
			if (qaPicklist != null) {
				for (PicklistEntryType p : qaPicklist.getPicklistEntry()) {
					PickListEntry e = new PickListEntry();
					e.setAddress(a);
					e.setMoniker(p.getMoniker());
					e.setScore(p.getScore().toString());
					e.setPostcode(p.getPostcode());
					e.setPartialAddress(p.getPartialAddress());
					e.setPicklist(p.getPicklist());

					pickList.add(e);
				}
			} else if (retDict.get("qaAddress") != null) {
				QAAddressType address = (QAAddressType) retDict.get("qaAddress");				
				PickListEntry e = new PickListEntry();
				List<AddressLineType> addressLineType = address.getAddressLine();
				e.setAddress(a);
				e.setL2(addressLineType.get(1).getLine());
				e.setL3(addressLineType.get(2).getLine());
				e.setPartialAddress(addressLineType.get(3).getLine());
				e.setL5(addressLineType.get(4).getLine());
				e.setPostcode(addressLineType.get(5).getLine());
				e.setInseeCode(addressLineType.get(6).getLine());

				pickList.add(e);
			}
			response.setValue("certifiedOk", false);
			response.setValue("pickList", pickList);

		} else if (verifyLevel != null && verifyLevel.value().equals("None")) {
			LOG.debug("address None");
			response.setFlash("Aucune addresse correspondante dans la base QAS");
		} 
	}

	public void select(ActionRequest request, ActionResponse response) {

		Address a = request.getContext().asType(Address.class);
		PickListEntry pickedEntry = null;

		if (a.getPickList().size() > 0) {

			//if (a.pickList*.selected.count { it == true} > 0)
			//	pickedEntry = a.pickList.find {it.selected == true}
			pickedEntry = a.getPickList().get(0);
			LOG.debug("select pickedEntry = {}", pickedEntry);
			String moniker = pickedEntry.getMoniker();
			if (moniker != null) {
				com.qas.web_2005_02.Address address = ads.select(GeneralService.getGeneral().getQasWsdlUrl(), moniker);
				LOG.debug("select address = {}", address);
				//addressL4: title="N° et Libellé de la voie"
				//addressL6: title="Code Postal - Commune"/>
				response.setValue("addressL2", address.getQAAddress().getAddressLine().get(1));
				response.setValue("addressL3", address.getQAAddress().getAddressLine().get(2));
				response.setValue("addressL4", address.getQAAddress().getAddressLine().get(3));
				response.setValue("addressL5", address.getQAAddress().getAddressLine().get(4));
				response.setValue("addressL6", address.getQAAddress().getAddressLine().get(5));
				response.setValue("inseeCode", address.getQAAddress().getAddressLine().get(6));
				response.setValue("certifiedOk", true);
				response.setValue("pickList", new ArrayList<QAPicklistType>());
			} 
			else  {
				LOG.debug("missing fields for pickedEntry: {}", pickedEntry);
				response.setValue("addressL2", pickedEntry.getL2());
				response.setValue("addressL3", pickedEntry.getL3());
				response.setValue("addressL4", pickedEntry.getPartialAddress());
				response.setValue("addressL5", pickedEntry.getL5());
				response.setValue("addressL6", pickedEntry.getPostcode());
				response.setValue("inseeCode", pickedEntry.getInseeCode());
				response.setValue("pickList", new ArrayList<QAPicklistType>());
				response.setValue("certifiedOk", true);
			}

		} 
		else 
			response.setFlash("NA");
	}

	public void export(ActionRequest request,ActionResponse response) throws IOException{

		AddressExport addressExport = request.getContext().asType(AddressExport.class);

		int size = (Integer) ads.export(addressExport.getPath());

		response.setValue("log", size+" adresses exportées");
	}

	public void importAddress(ActionRequest request, ActionResponse response) throws IOException{
		Import context = request.getContext().asType(Import.class);

		String path = context.getPath();
		String configPath = context.getConfigPath();
		String type = context.getTypeSelect();

		LOG.debug("using {} importer for config file: {} on directory: {}",type,configPath, path);


		File folder = new File(path);
		File xmlFile = new File(configPath);

		if (!folder.exists()) {
			response.setFlash("Dossier inacessible.");
		} else if (!xmlFile.exists()) {
			response.setFlash("Fichier de mapping inacessible.");
		} else { 
			Importer importer = null;

			if (type.equals("xml")) {
				LOG.debug("using XMLImporter");
				importer = new XMLImporter(injector, configPath, path);
			}
			else {
				LOG.debug("using CSVImporter");
				importer = new CSVImporter(injector, configPath, path);
			}
			Map<String,String[]> mappings = new HashMap<String,String[]>();
			String[] array = new String[1];
			array[1] = "Address.csv";
			mappings.put("contact.address", array);
			importer.run(mappings);
			//importer.run()

			response.setFlash("Import terminé.");
		}
	}

	public void viewMapOsm(ActionRequest request, ActionResponse response)  {

		Address address = request.getContext().asType(Address.class);
		String qString = address.getAddressL4()+" ,"+address.getAddressL6();
		LOG.debug("qString = {}", qString);
		BigDecimal lat = address.getLatit();
		BigDecimal lon = address.getLongit();
		try {
			if(BigDecimal.ZERO.compareTo(lat) == 0 ||  BigDecimal.ZERO.compareTo(lon) == 0 ){
				RESTClient restClient = new RESTClient("http://nominatim.openstreetmap.org/");
				Map<String,Object> mapQuery = new HashMap<String,Object>();
				mapQuery.put("q", qString);
				mapQuery.put("format", "xml");
				mapQuery.put("polygon", true);
				mapQuery.put("addressdetails", true);
				Map<String,Object> mapHeaders = new HashMap<String,Object>();
				mapHeaders.put("HTTP referrer", "axelor");
				Map<String,Object> mapResponse = new HashMap<String,Object>();
				mapResponse.put("path", "/search");
				mapResponse.put("accept", ContentType.JSON);
				mapResponse.put("query", mapQuery);
				mapResponse.put("headers", mapHeaders);
				mapResponse.put("connectTimeout", 5000);
				mapResponse.put("readTimeout", 10000);
				mapResponse.put("followRedirects", false);
				mapResponse.put("useCaches", false);
				mapResponse.put("sslTrustAllCerts", true);
				Response restResponse = restClient.get(mapResponse);
				GPathResult searchresults = new XmlSlurper().parseText(restResponse.getContentAsString());
				Iterator<Node> iterator = searchresults.childNodes();
				if(iterator.hasNext()){
					Node node = iterator.next();
					Map attributes = node.attributes();
					if(attributes.containsKey("lat") && attributes.containsKey("lon")){
						if(BigDecimal.ZERO.compareTo(lat) == 0)
							lat = new BigDecimal(node.attributes().get("lat").toString());
						if(BigDecimal.ZERO.compareTo(lon) == 0)
							lon = new BigDecimal(node.attributes().get("lon").toString());
						response.setValue("latit", lat);
						response.setValue("longit", lon);
					}
				}
			}
			if(BigDecimal.ZERO.compareTo(lat) != 0 && BigDecimal.ZERO.compareTo(lon) != 0){
				String url = String.format("map/oneMarker.html?x=%f&y=%f&z=18",lat,lon);
				LOG.debug("URL = {}",url);
				Map<String,Object> mapView = new HashMap<String,Object>();
				mapView.put("title", "Map");
				mapView.put("resource", url);
				mapView.put("viewType", "html");
				response.setView(mapView);
			}else response.setFlash(String.format("<B>%s</B> not found",qString));
			
		}catch(Exception e)  {
			TraceBackService.trace(response, e); 
		}
	}

	public JSONObject geocodeGoogle(String qString) {
		Map<String,Object> response = new HashMap<String,Object>();
		//http://maps.googleapis.com/maps/api/geocode/json?address=1600+Amphitheatre+Parkway,+Mountain+View,+CA&sensor=true_or_false

		// TODO inject the rest client, or better, run it in the browser
		RESTClient restClient = new RESTClient("https://maps.googleapis.com");
		Map<String,Object> responseQuery = new HashMap<String,Object>();
		responseQuery.put("address", qString);
		responseQuery.put("sensor", "false");
		Map<String,Object> responseMap = new HashMap<String,Object>();
		responseMap.put("path", "/maps/api/geocode/json");
		responseMap.put("accept", ContentType.JSON);
		responseMap.put("query", responseQuery);
		
		responseMap.put("connectTimeout", 5000);
		responseMap.put("readTimeout", 10000);
		responseMap.put("followRedirects", false);
		responseMap.put("useCaches", false);
		responseMap.put("sslTrustAllCerts", true);
		JSONObject restResponse = null;
		try {
			restResponse = new JSONObject(restClient.get(responseMap).getContentAsString());
			if(restResponse != null && restResponse.containsKey("results")){
				JSONObject result = (JSONObject)((JSONArray)restResponse.get("results")).get(0);
				if(result != null && result.containsKey("geometry"))
					restResponse = (JSONObject)((JSONObject) result.get("geometry")).get("location");
				else restResponse = null;
			}
			else restResponse = null;
				
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		/*
		log.debug("restResponse = {}", restResponse)
		log.debug("restResponse.parsedResponseContent.text = {}", restResponse.parsedResponseContent.text)
		 */
		//def searchresults = new JsonSlurper().parseText(restResponse.parsedResponseContent.text);
		/*
		LOG.debug("searchresults.status = {}", searchresults.status);
		if (searchresults.status == "OK") {
			/*
			log.debug("searchresults.results.size() = {}", searchresults.results.size())
			log.debug("searchresults.results[0] = {}", searchresults.results[0])
			log.debug("searchresults.results[0].address_components = {}", searchresults.results[0].address_components)
			log.debug("searchresults.results[0].geometry.location = {}", searchresults.results[0].geometry.location)
		 */
		/*
		def results = searchresults.results;

		if (results.size() > 1) {
			response.put("multiple", true);			
		}
		def firstPlaceFound = results[0];

		if (firstPlaceFound) {
			BigDecimal lat = new BigDecimal(firstPlaceFound.geometry.location.lat);
			BigDecimal lng = new BigDecimal(firstPlaceFound.geometry.location.lng);

			response.put("lat", lat.setScale(10, RoundingMode.HALF_EVEN));
			response.put("lng", lng.setScale(10, RoundingMode.HALF_EVEN));
		}
	*/
	//}

		return restResponse;
	}

	public void viewMapGoogle(ActionRequest request, ActionResponse response)  {
		Address address = request.getContext().asType(Address.class);
		String qString = address.getAddressL4()+" ,"+address.getAddressL6();
		BigDecimal lat = address.getLatit();
		BigDecimal lon = address.getLongit();
		LOG.debug("qString = {}", qString);
		try {
			if(BigDecimal.ZERO.compareTo(lat) == 0 || BigDecimal.ZERO.compareTo(lon) == 0){
				JSONObject googleResponse = geocodeGoogle(qString);
				if(googleResponse != null){
					if(BigDecimal.ZERO.compareTo(lat) == 0)
						lat = new BigDecimal(googleResponse.get("lat").toString());
					if(BigDecimal.ZERO.compareTo(lon) == 0)
						lon = new BigDecimal(googleResponse.get("lng").toString());
					response.setValue("latit", lat);
					response.setValue("longit", lon);
				}
			}
			if(BigDecimal.ZERO.compareTo(lat) != 0 && BigDecimal.ZERO.compareTo(lon) != 0){
				String url = String.format("map/gmaps.html?x=%f&y=%f&z=18",lat,lon);
				Map<String,Object> mapView = new HashMap<String,Object>();
				mapView.put("title", "Map");
				mapView.put("resource", url);
				mapView.put("viewType", "html");
				response.setView(mapView);
			}else response.setFlash(String.format("<B>%s</B> not found",qString));
		}
		catch(Exception e)  {
			TraceBackService.trace(response, e);
		}
	}

	public void viewMap(ActionRequest request, ActionResponse response)  {
		if (GeneralService.getGeneral().getMapApiSelect().equals("1")) {
			viewMapGoogle(request, response);
		} else {
			viewMapOsm(request, response);
		}
	}

	public void directionsMapGoogle(ActionRequest request, ActionResponse response)  {

		Address arrivalAddress = request.getContext().asType(Address.class);
		String arrivalString = arrivalAddress.getAddressL4()+" ,"+arrivalAddress.getAddressL6();
		LOG.debug("arrivalString = {}", arrivalString);

		Partner currPartner = uis.getUserPartner();
		Address departureAddress = currPartner.getDeliveryAddress();
		String departureString = departureAddress.getAddressL4()+" ,"+departureAddress.getAddressL6();
		LOG.debug("departureString = {}", departureString);

		try {
			BigDecimal depLat = departureAddress.getLatit();
			BigDecimal depLng = departureAddress.getLongit();
			BigDecimal arrLat = arrivalAddress.getLatit();
			BigDecimal arrLng =  arrivalAddress.getLongit();
			BigDecimal zero = new BigDecimal(0);
			LOG.debug("BEFORE departureLat = {}, departureLng={}", depLat,depLng);
			if ( depLat.equals(0) && depLng.equals(0)) {
				Map<String,Object> googleResponse = geocodeGoogle(departureString);
//				if (googleResponse.get("multiple") != null) {
//					response.setFlash("<B>$departureString</B> matches multiple locations. First selected."); 
//				}
				if(googleResponse != null){
					depLat = new BigDecimal(googleResponse.get("lat").toString());
					depLng = new BigDecimal(googleResponse.get("lng").toString());
				}
			}
			LOG.debug("departureLat = {}, departureLng={}", depLat,depLng);
			LOG.debug("BEFORE arrivalLat = {}, arrivalLng={}", arrLat,arrLng);
			if (depLat != zero && depLng != zero) {
				Map<String,Object> googleResponse = geocodeGoogle(arrivalString);
//				if (googleResponse.get("multiple") != null) {
//					response.setFlash("<B>$arrivalString</B> matches multiple locations. First selected.");
//				}
				if(googleResponse != null){
					arrLat = new BigDecimal(googleResponse.get("lat").toString());
					arrLng = new BigDecimal(googleResponse.get("lng").toString());
				}
			}
			LOG.debug("arrivalLat = {}, arrivalLng={}", arrLat,arrLng);
			if (arrLat != zero && arrLng != zero) {
				String url = String.format("map/directions.html?dx=%f&dy=%f&ax=%f&ay=%f",depLat,depLng,arrLat,arrLng);
				Map<String,Object> mapView = new HashMap<String,Object>();
				mapView.put("title", "Directions");
				mapView.put("resource", url);
				mapView.put("viewType", "html");
				response.setView(mapView);
				response.setValue("latit", arrLat);	
				response.setValue("longit", arrLng);	
			}
			else {
				response.setFlash("<B>$arrivalString</B> not found");
			}
		}
		catch(Exception e)  {
			TraceBackService.trace(response, e);
		}
	}

	public void directionsMap(ActionRequest request, ActionResponse response)  {
		Partner currPartner = uis.getUserPartner();
		Address departureAddress = currPartner.getDeliveryAddress();
		if (departureAddress != null) {
			if (GeneralService.getGeneral().getMapApiSelect().equals("1")) {
				directionsMapGoogle(request, response);
			} else {
				response.setFlash("Feature currently not available with Open Street Maps.");
			}
		} else {
			response.setFlash("Current user's partner delivery address not set");
		}
	}

	public void checkMapApi(ActionRequest request, ActionResponse response)  {
		response.setFlash("Not implemented yet!");
	}

	@SuppressWarnings("unchecked")
	@Transactional
	public void viewSalesMap(ActionRequest request, ActionResponse response)  {
		// Only allowed for google maps to prevent overloading OSM
		if (GeneralService.getGeneral().getMapApiSelect() == "1") {
			PartnerList partnerList = request.getContext().asType(PartnerList.class);

			File file = new File("/home/axelor/www/HTML/latlng_"+partnerList.getId()+".csv");
			//file.write("latitude,longitude,fullName,turnover\n");

			Iterator<Partner> it = (Iterator<Partner>) partnerList.getPartnerSet();

			while(it.hasNext()) {

				Partner partner = it.next();
				//def address = partner.mainInvoicingAddress
				if (partner.getMainInvoicingAddress() != null) {
					partner.getMainInvoicingAddress().getId();
					Address address = Address.find(partner.getMainInvoicingAddress().getId());
					if (!(address.getLatit() != null && address.getLongit() != null)) {
						String qString = address.getAddressL4()+" ,"+address.getAddressL6();
						LOG.debug("qString = {}", qString);

						Map<String,Object> googleResponse = geocodeGoogle(qString);
						address.setLatit((BigDecimal) googleResponse.get("lat"));
						address.setLongit((BigDecimal) googleResponse.get("lng"));
						address.save();
					}
					if (address.getLatit() != null && address.getLongit() != null) {
						//def turnover = Invoice.all().filter("self.partner.id = ? AND self.status.code = 'val'", partner.id).fetch().sum{ it.inTaxTotal }
						List<Invoice> listInvoice = (List<Invoice>) Invoice.all().filter("self.partner.id = ?", partner.getId()).fetch();
						BigDecimal turnover = BigDecimal.ZERO;
						for(Invoice invoice: listInvoice) {
							turnover.add(invoice.getInTaxTotal());
						}
						/*
						file.withWriterAppend('UTF-8') {
							it.write("${address.latit},${address?.longit},${partner.fullName},${turnover?:0.0}\n")
						}
						 */
					}
				}
			}
			//response.values = [partnerList : partnerList]
			String url = "";
			if (partnerList.getIsCluster())
				url = "http://localhost/HTML/cluster_gmaps_xhr.html?file=latlng_"+partnerList.getId()+".csv";
			else
				url = "http://localhost/HTML/gmaps_xhr.html?file=latlng_"+partnerList.getId()+".csv";

			Map<String,Object> mapView = new HashMap<String,Object>();
			mapView.put("title", "Sales map");
			mapView.put("resource", url);
			mapView.put("viewType", "html");
			response.setView(mapView);
			//response.reload = true

		} else {
			response.setFlash("Not implemented for OSM");
		}
	}
}
