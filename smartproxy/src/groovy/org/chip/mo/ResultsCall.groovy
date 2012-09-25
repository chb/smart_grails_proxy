package org.chip.mo;

import org.apache.commons.logging.Log;
import org.chip.managers.VitalSignsManager;
import org.chip.mo.exceptions.MOCallException;
import org.chip.mo.exceptions.InvalidRequestException;
import org.chip.mo.model.Event;
import org.chip.rdf.Vitals;
import org.chip.rdf.vitals.*;
import org.chip.readers.EventsReader;
import org.codehaus.groovy.grails.commons.ConfigurationHolder;

import groovy.xml.MarkupBuilder;
import groovy.xml.StreamingMarkupBuilder;
import groovyx.net.http.AsyncHTTPBuilder;
import groovyx.net.http.ContentType;

import org.apache.commons.logging.LogFactory;

import smartproxy.EncounterService;

import groovy.xml.StreamingMarkupBuilder

/**
* ResultsCall.groovy
* Purpose: Represents the Millennium Object call to filter and return Results information for a specific Person id.
* Only clinical information is returned.
* @author mkapoor
* @version Jun 19, 2012 12:53:03 PM
*/
class ResultsCall extends MilleniumObjectCall{
	private static final Log log = LogFactory.getLog(this)
	
	protected static final String LIMITPARAM = "limit"
	protected static final String OFFSETPARAM = "offset"
	
	static Map eventSetNames
	static int eventCount
	
	//Grab eventSetNames and the number of events to be fetched from configuration file
	static{
		def config = ConfigurationHolder.config
		eventSetNames = config.cerner.mo.eventSetNames
		eventCount = config.cerner.mo.eventCount
	}
	
	VitalSignsManager vitalSignsManager
	EventsReader eventsReader
	EncounterService encounterService
	
	def init(){
		super.init()
		transaction = 'ReadResultsByCount'
		targetServlet = 'com.cerner.results.ResultsServlet'
		encounterService = new EncounterService()
	}
	
	/**
	* The Workhorse method. (overrides the base implementation)
	* -Creates the outgoing MO request.
	* -calls makeRestCall to make the actual MO call.
	* -calls readResponse to create an RDF Model from the MO response.
	* @param recordId
	* @param moURL
	* @return
	*/
   def makeCall(recordId, moURL) throws MOCallException{
	   Vitals vitals
	   boolean vitalsFound = false
	   boolean lastCallMade = false
	   
	   requestParams.put(RECORDIDPARAM, recordId)
	   
	   String limitParam = requestParams.get(ResultsCall.LIMITPARAM)
	   int limit
	   if(null!=limitParam){
		   limit = Integer.parseInt(limitParam)
	   }
	   
	   while(!vitalsFound &&!lastCallMade){
		   //Refresh the vitalSignsManager and eventsReader before making a new call.
		   vitalSignsManager = new VitalSignsManager()
		   eventsReader = new EventsReader()
		   
		   //Read vitalSigns from the cache(database)
		   vitals = vitalSignsManager.getVitalsFromCache(requestParams)
		   if(vitals.vitalSignsSet.size()==limit){
			   	//Sufficient vitals information retrieved from cache.
			   vitalsFound = true
		   }else{
		   		//Sufficient vitals information not present in cache. Make a MO call to add to cache
			   	def resultsMap
			   	try{
				   
				   def requestXML = createRequest()
			   
				   long l1 = new Date().getTime()
				   //Make multiple async calls and obtain the results for all calls in a map.
				   resultsMap = makeAsyncCall(requestXML, moURL, recordId)
				   
				   long l2 = new Date().getTime()
				   log.info("Call for transaction: "+transaction+" took "+(l2-l1)/1000)
				} catch (InvalidRequestException ire){
				   log.error(ire.exceptionMessage +" for "+ recordId +" because " + ire.rootCause)
				   throw new MOCallException(ire.exceptionMessage, ire.statusCode, ire.rootCause)
				}
				
				//Aggregate the responses from various async calls into a single MO Response document
				long l1 = new Date().getTime()
				def respXml
				try{
					respXml = aggregateResults(resultsMap)
				}catch(MOCallException moce){
					//No results returned. last call has been made
					lastCallMade=true
				}
				long l2 = new Date().getTime()
				log.info("Aggregating MO response took "+(l2-l1)/1000)
				
				//Parse the aggregated response xml and persist vitalsigns into the database.
				if(respXml){
					readResponse(respXml)
					
					markEncountersUsed()
				}
				long l3 = new Date().getTime()
				log.info("Reading and processing MO response took "+(l3-l2)/1000)
		   }
	   }
		return vitals
   }
   
   /**
    * Reads in the request template as requestXML
    * Makes multiple Async calls by iterating over the set of events for which MO Call has to be made.
    * @param requestXML
    * @param moURL
    * @param recordId
    * @return
    * @throws MOCallException
    */
   def makeAsyncCall(requestXML, moURL, recordId) throws MOCallException{
	   def async = new AsyncHTTPBuilder(
		   poolSize : 16,
		   uri : moURL+targetServlet,
		   contentType : ContentType.XML)
	
	   Map resultsMap = new HashMap()
	   eventSetNames.each {key, eventSetName->
		   //create the MO request from template
		   String subRequestXml=requestXML.replace('EVENTSETNAME',eventSetName)
		   
		   //Make the async call
		   def result = async.post(body:subRequestXml, requestContentType : ContentType.XML) { resp, xml ->
			   return xml
		   }
		   
		   //Put the response in a map.
		   resultsMap.put(key, result)
	   }
	   
	   //Iterate over the response map, checking until all calls have returned.
	   resultsMap.each{key, result->
		   while(!result.done){
			   log.info("ASYNC: Waiting for MO results")
			   Thread.sleep(500)
		   }
		   log.info(key+ " ASYNC MO call returned")
		   //Handle any exceptions as indicated in the MO Response
		   handleExceptions(result.get(), recordId)
	   }
	   
	   return resultsMap
   }
   
   def aggregateResults(resultsMap){
	   def clinicalEvents
	   def mapIdx=0
	   resultsMap.each{key, result->
		   //get the MO reply message from the http response
		   def replyMessage = result.get()
		   
		   //If it's the first element in the map, extract the ClinicalEvents xml element
		   if(mapIdx==0){
			   def payload = replyMessage.Payload
			   if(payload.size()==1){
				   clinicalEvents=replyMessage.Payload.Results.ClinicalEvents
				   mapIdx++
			   }
		   }else{//If we are past the first element, just append all Results elements to the ClinicalEvents xml element
			   def numericResults=replyMessage.Payload.Results.ClinicalEvents.NumericResult
			   def codedResults = replyMessage.Payload.Results.ClinicalEvents.CodedResult
			   
				for (numericResult in numericResults){
					clinicalEvents.appendNode(numericResult)
				}   
				
				for (codedResult in codedResults){
					clinicalEvents.appendNode(codedResult)
				}
		   }
	   }
	   
	   //If mapIdx is still 0, that means no payload was read in from the MO responses.
	   //Throw MOCallException so it can be caught to indicate that the last call has been made and there are no more results.
	   if (mapIdx==0){
		   throw new MOCallException("No results returned", 404, "No more results for the current patient")
	   }
	   
	   //Converted the aggregated response to a single xml doc
	   def outputBuilder = new StreamingMarkupBuilder()
	   outputBuilder.encoding="UTF-8"
	   def writer = new StringWriter()
	   writer<<outputBuilder.bind {mkp.yield clinicalEvents}
	   String aggregatedResponse = writer.toString()
	   def aggregatedResponseXml = new XmlSlurper().parseText(aggregatedResponse)
	   
	   log.info("Printing aggregated response")
	   log.info(aggregatedResponse)
	   log.info("Done printing aggregated response")
	   
	   return aggregatedResponseXml
   }
	
	/**
	* Pass the encountersById map containing all Encounters mapped to their id in.
	* This will be used by the manager to assign an encounter to each VitalSigns object it creates
	* Record results with the vitalSignsManager to process later.
	* 
	* @param moResponse
	* @return vitals
	*/
	def readResponse(moResponseXml)throws MOCallException{
		if(moResponseXml !=null){
			def recordId = (String)requestParams.get(RECORDIDPARAM)
			
			//parse the moResonse and pass events to the vitalSignsManager
			eventsReader.read(moResponseXml)
			Map eventsByParentEventId = eventsReader.getEvents()
			vitalSignsManager.recordEvents(eventsByParentEventId)
			
			//pass encounters to vitalSignsManager
			Map encountersById = (HashMap)requestParams.get(MO_RESPONSE_PARAM)
			//If no encounter call was made, encountersById will be null.
			//Fetch encounters from the db
			if(null==encountersById){
				encountersById = encounterService.getEncountersByIdForPatient(recordId)
			}
			vitalSignsManager.recordEncounters(encountersById)
			
			//Process away.
			vitalSignsManager.processEvents()
		}
			
		Vitals vitalsFromResponse = vitalSignsManager.getVitalsFromResponse()
		return vitalsFromResponse
	}
	
	/**
	 * Mark encounters for which events were returned as "used"
	 * @return
	 */
	def markEncountersUsed(){
		encounterService.markEncountersUsed(eventsReader.getReturnedEncounterIdsSet())
	}
	
	/**
	* Generates MO requests to
	* - Get Vitals for a list of Encounters and a given patient id
	* @param recordId
	* @return
	*/
	def generatePayload(){
		def recordId = (String)requestParams.get(RECORDIDPARAM)
		builder.PersonId(recordId)
		builder.EventCount(eventCount)//We are only interested in the top 999 results for now.
		builder.EventSet(){
			Name('EVENTSETNAME')
		}
		
		Set encounterIds = getEncounterIds(recordId)
		
		// MO Server chokes on an empty encounter IDs list.
		// Log it and throw an exception.
		if (encounterIds.size() == 0) {
			throw new InvalidRequestException("Error creating MO Request", 500, "No Encounters Found")
		}

		builder.EncounterIds(){
			encounterIds.each{encounterId->
				EncounterId(encounterId)
			}
		}
	}
	
	def getEncounterIds(patientId){
		Set encounterIds
		Map encountersById = (HashMap)requestParams.get(MO_RESPONSE_PARAM)
		
		//If no encounter call was made, encountersById will be null.
		//Fetch encounter ids from the db
		if (null==encountersById){
			encounterIds = encounterService.getEncounterIdsForPatient(patientId)
		}else{
			encounterIds = encountersById.keySet()
		}
		return encounterIds
	}
}