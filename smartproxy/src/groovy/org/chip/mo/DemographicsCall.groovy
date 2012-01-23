package org.chip.mo

import org.chip.rdf.Demographics

class DemographicsCall extends MilleniumObjectCall{
	
	public static final String PERSON_ALIAS_TYPE_MEANING_MRN="MRN"
	
	def init(){
		super.init()
		transaction = 'ReadPersonById'
		targetServlet = 'com.cerner.person.PersonServlet'
	}
	
	/**
	* Generate appropriate MO request xml payload
	* @param recordId
	* @return
	*/
   def generatePayload(requestParams){
	   def recordId = (String)requestParams.get(RECORDIDPARAM)
	   builder.PersonId(recordId)
	   builder.AddressesIndicator('true')
	   builder.PersonAliasesIndicator('true') 
   }
   
   /**
   * Reads in the MO response and converts it to a Demographics object
   * @param moResponse
   * @return
   */
  def readResponse(moResponse){
	  
	  def replyMessage = moResponse.getData()
	  def payload= replyMessage.Payload
	  
	  def person = payload.Person
	  def birthDateTime = person.BirthDateTime.text()
	  if(birthDateTime.length()>0){
		  birthDateTime=person.BirthDateTime.text().substring(0, 10)
	  }
	  def givenName=person.FirstName.text()
	  def familyName=person.LastName.text()
	  def gender=person.Gender.Meaning.text().toLowerCase()
	  def zipcode=""
	  if(person.Addresses.Address.Zipcode.text().length()>=5){
		  zipcode=person.Addresses.Address.Zipcode.text().substring(0,5)
	  }
	  def mrn=""
	  def personalAliases = person.PersonAliases
	  person.PersonAliases.PersonAlias.each{ personAlias->
		  if(personAlias.PersonAliasType.Meaning.text()==PERSON_ALIAS_TYPE_MEANING_MRN){
			  mrn = personAlias.Alias.text()
		  }
	  }
	  return new Demographics(birthDateTime, givenName, familyName, gender, zipcode, mrn)
  }
}
