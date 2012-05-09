package org.chip.readers

import java.util.Map;

import org.chip.managers.VitalSignsManager;
import org.chip.mo.exceptions.MOCallException;
import org.chip.mo.model.Event;
import org.chip.rdf.vitals.VitalSign;
import org.codehaus.groovy.grails.commons.ConfigurationHolder;

class EventsReader {
	
	/**
	* eventCodesMap
	*/
   static final Map ecm
	
	static final Map bpEventCodeByComplexBPEventCode
	static final Map bodyPositionByComplexBPEventCode
	static final Collection vitalEventCodes
	
	static{
		def config = ConfigurationHolder.config
		ecm = config.cerner.mo.eventCode
		
		bpEventCodeByComplexBPEventCode = new HashMap()
		bpEventCodeByComplexBPEventCode.put(ecm.get("EVENTCODESYSSUPINE"), ecm.get("EVENTCODESYS"))
		bpEventCodeByComplexBPEventCode.put(ecm.get("EVENTCODESYSSITTING"), ecm.get("EVENTCODESYS"))
		bpEventCodeByComplexBPEventCode.put(ecm.get("EVENTCODESYSSTANDING"), ecm.get("EVENTCODESYS"))
		bpEventCodeByComplexBPEventCode.put(ecm.get("EVENTCODEDIASUPINE"), ecm.get("EVENTCODEDIA"))
		bpEventCodeByComplexBPEventCode.put(ecm.get("EVENTCODEDIASITTING"), ecm.get("EVENTCODEDIA"))
		bpEventCodeByComplexBPEventCode.put(ecm.get("EVENTCODEDIASTANDING"), ecm.get("EVENTCODEDIA"))
		
		bodyPositionByComplexBPEventCode = new HashMap()
		bodyPositionByComplexBPEventCode.put(ecm.get("EVENTCODESYSSUPINE"), 'Supine')
		bodyPositionByComplexBPEventCode.put(ecm.get("EVENTCODESYSSITTING"), 'Sitting')
		bodyPositionByComplexBPEventCode.put(ecm.get("EVENTCODESYSSTANDING"), 'Standing')
		bodyPositionByComplexBPEventCode.put(ecm.get("EVENTCODEDIASUPINE"), 'Supine')
		bodyPositionByComplexBPEventCode.put(ecm.get("EVENTCODEDIASITTING"), 'Sitting')
		bodyPositionByComplexBPEventCode.put(ecm.get("EVENTCODEDIASTANDING"), 'Standing')
		
		vitalEventCodes = ecm.values()
	}
	
	private Map<String, List> eventsByParentEventId
	
	private List<Event> eventsList
	
	public EventsReader(){
		eventsByParentEventId = new HashMap()
		eventsList = new ArrayList()
	}
	
	public Map getEvents(){
		return eventsByParentEventId
	} 
	
	public read(moResponse){
		def replyMessage = moResponse.getData()
		def payload= replyMessage.Payload
		processPayload(payload)
		groupEvents()
		splitComplexEvents()
	}
	
	public processPayload(payload){
			eventsByParentEventId = new HashMap()
			//int i = 0
			//long l1 = new Date().getTime()
			//Create events for all Numeric Results in the moResponse
			//Group the events by parent event id (or timestamps)
			payload.Results.ClinicalEvents.NumericResult.each{ currentNumericResult->
					//i++
				def currentEventCode=currentNumericResult.EventCode.Value.text()
				if(vitalEventCodes.contains(currentEventCode)){
					Event currentEvent = new Event()
					currentEvent.encounterId = currentNumericResult.EncounterId.text()
					currentEvent.eventCode = currentEventCode
					currentEvent.value = currentNumericResult.Value.text()
					currentEvent.eventId = currentNumericResult.EventId.text()
					currentEvent.parentEventId = currentNumericResult.ParentEventId.text()
					currentEvent.eventEndDateTime = currentNumericResult.EventEndDateTime.text()
					currentEvent.updateDateTime = currentNumericResult.UpdateDateTime.text()
					currentEvent.recordId = currentNumericResult.PersonId.text()
					
					//Run a quick validation on the value and add the event to the list only if the value is valid
					if(valueIsValid(currentEvent.value)){
						eventsList.add(currentEvent)
					}
				}
			}
			//Create events for all Coded Results in the moResponse
			//Group the events by parent event id (or timestamps)
			payload.Results.ClinicalEvents.CodedResult.each{ currentCodedResult->
					//i++
				def currentEventCode=currentCodedResult.EventCode.Value.text()
				if(vitalEventCodes.contains(currentEventCode)){
					Event currentEvent = new Event()
					currentEvent.encounterId = currentCodedResult.EncounterId.text()
					currentEvent.eventCode = currentEventCode
					currentEvent.eventTag = currentCodedResult.EventTag.text()
					currentEvent.eventId = currentCodedResult.EventId.text()
					currentEvent.parentEventId = currentCodedResult.ParentEventId.text()
					currentEvent.eventEndDateTime = currentCodedResult.EventEndDateTime.text()
					currentEvent.updateDateTime = currentCodedResult.UpdateDateTime.text()
					currentEvent.recordId = currentCodedResult.PersonId.text()
					
					eventsList.add(currentEvent)
				}
			}
			//println("number of results returned : " + i)
			//long l2 = new Date().getTime()
			//println("vitals reading moresponse took: "+(l2-l1)/1000)
	}
	
	def groupEvents(){
		eventsList.each {event->
			
			//1. Match on parent event id
			def mappedEventsList = eventsByParentEventId.get(event.parentEventId)
			
			if(mappedEventsList==null){//2. No match on parent event id, try to match by end datetime and update datetime.
				mappedEventsList = matchByEndAndUpdateDateTimes(event.eventEndDateTime, event.updateDateTime)
				
				if(mappedEventsList==null){//3. No match on both datetimes, create a new list.
					mappedEventsList = new ArrayList()
					eventsByParentEventId.put(event.parentEventId, mappedEventsList)
				}
			}
			
			mappedEventsList.add(event)
		}
	}
	
	def matchByEndAndUpdateDateTimes(String eventEndDateTime, String updateDateTime){
		def ret = null
		def found = false
		eventsByParentEventId.each{key, value->
			if(!found && (value.size()>0)){
				if(value.get(0).eventEndDateTime.equals(eventEndDateTime) && value.get(0).updateDateTime.equals(updateDateTime)){
					ret = value
					found=true
				} 
			}
		}
		return ret
	}
	
	/**
	 * This method extracts atomic bp events from complex bp events.
	 * 
	 * Example of a complex bp event: 
	 * 	MO can return a single vital sign result which contains a 'systolic bp' reading at 'supine' body position. (SYSTOLIC-98-SUPINE)
	 * 	This will usually be accompanied by a 'diastolic bp' reading at 'supine' body position.(DIASTOLIC-68-SUPINE)
	 * 
	 * This method will break the two results into 3 atomic results. One each for the two bp values (systolic and diastolic) and one for body position.
	 * So we'll get 'SYSTOLIC-98', 'DIASTOLIC-68' and 'SUPINE' . 
	 * These three atomic events will be grouped together and mapped by a unique identifier (like a parent event id).
	 * 
	 * Note: Often, if not always, complex bp events for 'standing', 'sitting' and 'supine' body positions will occur together.
	 * 	In this case the method will split 6 complex events into 9 atomic events.
	 * 	Grouping 3 events together by body position 
	 */
	def splitComplexEvents(){
		Map atomicEventsbyParentEventId = new HashMap()
		List complexParentEventIds = new ArrayList()
				
		//iterate through all event lists
		eventsByParentEventId.each{parentEventId, events ->
				def currentEventCode = events.get(0).getEventCode()
				//Check if there is a mapping for the first event's event code in the complex events map.
				if (bpEventCodeByComplexBPEventCode.get(currentEventCode)!=null){
					//Step 1
					//Mapping Found. Assume all events in this list are complex.
					//Proceed with splitting the complex events. 
					List supineBPEvents = new ArrayList()
					List standingBPEvents = new ArrayList()
					List sittingBPEvents = new ArrayList()
					
					Map bpEventsByBodyPosition = new HashMap()
					bpEventsByBodyPosition.put("Supine", supineBPEvents)
					bpEventsByBodyPosition.put("Sitting", sittingBPEvents)
					bpEventsByBodyPosition.put("Standing", standingBPEvents)
					
					events.each{complexEvent->
						
						def bpEventCode = bpEventCodeByComplexBPEventCode.get(complexEvent.eventCode)
						def bodyPosition = bodyPositionByComplexBPEventCode.get(complexEvent.eventCode)
						
						if(bpEventsByBodyPosition.get(bodyPosition).size()>0){
							//list for this body position has already been initialized. 
							//Only add the bp value
							bpEventsByBodyPosition.get(bodyPosition).add(
								new Event(encounterId: complexEvent.encounterId,
										eventCode: bpEventCode,
										value: complexEvent.value,
										eventId: complexEvent.eventId,
										parentEventId: parentEventId,
										eventEndDateTime: complexEvent.eventEndDateTime,
										updateDateTime: complexEvent.updateDateTime)
								)
						}else{
							// new list for the body position.
							// Add both the bp value and body position
							bpEventsByBodyPosition.get(bodyPosition).add(
								new Event(encounterId: complexEvent.encounterId,
										eventCode: bpEventCode,
										value: complexEvent.value,
										eventId: complexEvent.eventId,
										parentEventId: parentEventId,
										eventEndDateTime: complexEvent.eventEndDateTime,
										updateDateTime: complexEvent.updateDateTime))
							bpEventsByBodyPosition.get(bodyPosition).add(
								new Event(encounterId: complexEvent.encounterId,
										eventCode: ecm.get("EVENTCODEPOSITION"),
										eventTag: bodyPosition,
										eventId: complexEvent.eventId,
										parentEventId: parentEventId,
										eventEndDateTime: complexEvent.eventEndDateTime,
										updateDateTime: complexEvent.updateDateTime)
								)
						}
					}
					
					//Step 2
					//Add the new events to the atomicEventsbyParentEventId map
					//2 complex events are split and the results merged to form a single list of atomic events.
					//The eventId of the first containing event is used as the key to this new atomic events list.
					bpEventsByBodyPosition.each{bodyPositionKey, bodyPositionList ->
						atomicEventsbyParentEventId.put(bodyPositionList.get(0).getEventId(), bodyPositionList)
					}
					//Also store the parentEventId of the complex events list.
					complexParentEventIds.add(parentEventId)
				}
		}
		
			
		//Step 3
		//Add the mapped atomic event lists to our original eventsList map.
		eventsByParentEventId.putAll(atomicEventsbyParentEventId)
		
		//Step 4
		//Remove mappings for all complexEventsList
		complexParentEventIds.each{
			eventsByParentEventId.remove(it)
		}
		
		eventsByParentEventId.each {
			assert it.value.size() > 0, "Found an empty event set: " + it.value
		}
		
	}
	
	def valueIsValid(currentValue){
		if (currentValue==null) return false
		if (currentValue.equals("")) return false
		if (currentValue.equals("0")) return false
		if (currentValue.equals("0.0")) return false
		
		return true
	}
}



