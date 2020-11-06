package ca.uhn.fhir.empi.util;

/*-
 * #%L
 * HAPI FHIR - Enterprise Master Patient Index
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.empi.api.EmpiConstants;
import ca.uhn.fhir.empi.api.IEmpiSettings;
import ca.uhn.fhir.empi.log.Logs;
import ca.uhn.fhir.empi.model.CanonicalEID;
import ca.uhn.fhir.empi.model.CanonicalIdentityAssuranceLevel;
import ca.uhn.fhir.empi.model.EmpiTransactionContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.util.FhirTerser;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseBackboneElement;
import org.hl7.fhir.instance.model.api.IBaseCoding;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Person;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ca.uhn.fhir.context.FhirVersionEnum.DSTU3;
import static ca.uhn.fhir.context.FhirVersionEnum.R4;

@Service
public class PersonHelper {
	private static final Logger ourLog = Logs.getEmpiTroubleshootingLog();

	@Autowired
	private IEmpiSettings myEmpiConfig;
	@Autowired
	private EIDHelper myEIDHelper;

	private final FhirContext myFhirContext;

	@Autowired
	public PersonHelper(FhirContext theFhirContext) {
		myFhirContext = theFhirContext;
	}

	/**
	 * Given a Person, extract all {@link IIdType}s for the linked targets.
	 *
	 * @param thePerson the Person to extract link IDs from.
	 * @return a Stream of {@link IIdType}.
	 */
	public Stream<IIdType> getLinkIds(IBaseResource thePerson) {
		// TODO we can't rely on links anymore, as the provided resource is likely not to have thoem
		// need a way to pull those from the underlying MDM functionality
		// how do we pull link IDs now???
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				Person personR4 = (Person) thePerson;
				return personR4.getLink().stream()
					.map(Person.PersonLinkComponent::getTarget)
					.map(IBaseReference::getReferenceElement)
					.map(IIdType::toUnqualifiedVersionless);
			case DSTU3:
				org.hl7.fhir.dstu3.model.Person personStu3 = (org.hl7.fhir.dstu3.model.Person) thePerson;
				return personStu3.getLink().stream()
					.map(org.hl7.fhir.dstu3.model.Person.PersonLinkComponent::getTarget)
					.map(IBaseReference::getReferenceElement)
					.map(IIdType::toUnqualifiedVersionless);
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	/**
	 * Determine whether or not the given {@link IBaseResource} person contains a link to a particular {@link IIdType}
	 *
	 * @param thePerson     The person to check
	 * @param theResourceId The ID to check.
	 * @return A boolean indicating whether or not there was a contained link.
	 */
	public boolean containsLinkTo(IBaseResource thePerson, IIdType theResourceId) {
		Stream<IIdType> links = getLinkIds(thePerson);
		return links.anyMatch(link -> link.getValue().equals(theResourceId.getValue()));
	}

	/**
	 * Create or update a link from source {@link IBaseResource} to the target {@link IIdType}, with the given {@link CanonicalIdentityAssuranceLevel}.
	 *
	 * @param thePerson                 The person who's link needs to be updated.
	 * @param theResourceId             The target of the link
	 * @param canonicalAssuranceLevel   The level of certainty of this link.
	 * @param theEmpiTransactionContext
	 */
	public void addOrUpdateLink(IBaseResource thePerson, IIdType theResourceId, @Nonnull CanonicalIdentityAssuranceLevel canonicalAssuranceLevel, EmpiTransactionContext theEmpiTransactionContext) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				handleLinkUpdateR4(thePerson, theResourceId, canonicalAssuranceLevel, theEmpiTransactionContext);
				break;
			case DSTU3:
				handleLinkUpdateDSTU3(thePerson, theResourceId, canonicalAssuranceLevel, theEmpiTransactionContext);
				break;
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	private void handleLinkUpdateDSTU3(IBaseResource thePerson, IIdType theResourceId, CanonicalIdentityAssuranceLevel theCanonicalAssuranceLevel, EmpiTransactionContext theTransactionLogMessages) {
		if (theCanonicalAssuranceLevel == null) {
			ourLog.warn("Refusing to update or add a link without an Assurance Level.");
			return;
		}

		org.hl7.fhir.dstu3.model.Person person = (org.hl7.fhir.dstu3.model.Person) thePerson;
		if (!containsLinkTo(thePerson, theResourceId)) {
			person.addLink().setTarget(new org.hl7.fhir.dstu3.model.Reference(theResourceId)).setAssurance(theCanonicalAssuranceLevel.toDstu3());
			logLinkAddMessage(thePerson, theResourceId, theCanonicalAssuranceLevel, theTransactionLogMessages);
		} else {
			person.getLink().stream()
				.filter(link -> link.getTarget().getReference().equalsIgnoreCase(theResourceId.getValue()))
				.findFirst()
				.ifPresent(link -> {
					logLinkUpdateMessage(thePerson, theResourceId, theCanonicalAssuranceLevel, theTransactionLogMessages, link.getAssurance().toCode());
					link.setAssurance(theCanonicalAssuranceLevel.toDstu3());
				});
		}
	}

	private void logLinkAddMessage(IBaseResource thePerson, IIdType theResourceId, CanonicalIdentityAssuranceLevel theCanonicalAssuranceLevel, EmpiTransactionContext theEmpiTransactionContext) {
		theEmpiTransactionContext.addTransactionLogMessage("Creating new link from " + (StringUtils.isBlank(thePerson.getIdElement().toUnqualifiedVersionless().getValue()) ? "new Person" : thePerson.getIdElement().toUnqualifiedVersionless()) + " -> " + theResourceId.toUnqualifiedVersionless() + " with IdentityAssuranceLevel: " + theCanonicalAssuranceLevel.name());
	}

	private void logLinkUpdateMessage(IBaseResource thePerson, IIdType theResourceId, CanonicalIdentityAssuranceLevel canonicalAssuranceLevel, EmpiTransactionContext theEmpiTransactionContext, String theOriginalAssuranceLevel) {
		theEmpiTransactionContext.addTransactionLogMessage("Updating link from " + thePerson.getIdElement().toUnqualifiedVersionless() + " -> " + theResourceId.toUnqualifiedVersionless() + ". Changing IdentityAssuranceLevel: " + theOriginalAssuranceLevel + " -> " + canonicalAssuranceLevel.name());
	}

	private void handleLinkUpdateR4(IBaseResource thePerson, IIdType theResourceId, CanonicalIdentityAssuranceLevel canonicalAssuranceLevel, EmpiTransactionContext theEmpiTransactionContext) {
		if (canonicalAssuranceLevel == null) {
			ourLog.warn("Refusing to update or add a link without an Assurance Level.");
			return;
		}

		Person person = (Person) thePerson;
		if (!containsLinkTo(thePerson, theResourceId)) {
			person.addLink().setTarget(new Reference(theResourceId)).setAssurance(canonicalAssuranceLevel.toR4());
			logLinkAddMessage(thePerson, theResourceId, canonicalAssuranceLevel, theEmpiTransactionContext);
		} else {
			person.getLink().stream()
				.filter(link -> link.getTarget().getReference().equalsIgnoreCase(theResourceId.getValue()))
				.findFirst()
				.ifPresent(link -> {
					logLinkUpdateMessage(thePerson, theResourceId, canonicalAssuranceLevel, theEmpiTransactionContext, link.getAssurance().toCode());
					link.setAssurance(canonicalAssuranceLevel.toR4());
				});
		}
	}


	/**
	 * Removes a link from the given {@link IBaseResource} to the target {@link IIdType}.
	 *
	 * @param thePerson                 The person to remove the link from.
	 * @param theResourceId             The target ID to remove.
	 * @param theEmpiTransactionContext
	 */
	public void removeLink(IBaseResource thePerson, IIdType theResourceId, EmpiTransactionContext theEmpiTransactionContext) {
		if (!containsLinkTo(thePerson, theResourceId)) {
			return;
		}
		theEmpiTransactionContext.addTransactionLogMessage("Removing PersonLinkComponent from " + thePerson.getIdElement().toUnqualifiedVersionless() + " -> " + theResourceId.toUnqualifiedVersionless());
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				Person person = (Person) thePerson;
				List<Person.PersonLinkComponent> links = person.getLink();
				links.removeIf(component -> component.hasTarget() && component.getTarget().getReference().equals(theResourceId.getValue()));
				break;
			case DSTU3:
				org.hl7.fhir.dstu3.model.Person personDstu3 = (org.hl7.fhir.dstu3.model.Person) thePerson;
				personDstu3.getLink().removeIf(component -> component.hasTarget() && component.getTarget().getReference().equalsIgnoreCase(theResourceId.getValue()));
				break;
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	/**
	 * Creates a copy of the specified resource. This method will carry over resource EID if it exists. If it does not exist,
	 * a randomly generated UUID EID will be created.
	 *
	 * @param <T>                 Supported MDM resource type (e.g. Patient, Practitioner)
	 * @param theIncomingResource The resource that will be used as the starting point for the MDM linking.
	 */
	public <T extends IAnyResource> T createSourceResourceFromEmpiTarget(T theIncomingResource) {
		validateContextSupported();

		// get a ref to the actual ID Field
		RuntimeResourceDefinition resourceDefinition = myFhirContext.getResourceDefinition(theIncomingResource);
		IBaseResource newSourceResource = resourceDefinition.newInstance();

		// hapi has 2 metamodels: for children and types
		BaseRuntimeChildDefinition sourceResourceIdentifier = resourceDefinition.getChildByName("identifier");

		cloneAllExternalEidsIntoNewSourceResource(sourceResourceIdentifier, theIncomingResource, newSourceResource);

		addHapiEidIfNoExternalEidIsPresent(newSourceResource, sourceResourceIdentifier);

		populateMetaTag(newSourceResource);

		return (T) newSourceResource;
	}

	/**
	 * If there are no external EIDs on the incoming resource, create a new HAPI EID on the new SourceResource.
	 */
	//TODO GGG ask james if there is any way we can convert this canonical EID into a generic STU-agnostic IBase.
	private <T extends IAnyResource> void addHapiEidIfNoExternalEidIsPresent(IBaseResource theNewSourceResource, BaseRuntimeChildDefinition theSourceResourceIdentifier) {
		List<CanonicalEID> eidsToApply = myEIDHelper.getExternalEid(theNewSourceResource);
		if (!eidsToApply.isEmpty()) {
			return;
		}
		CanonicalEID hapiEid = myEIDHelper.createHapiEid();
		theSourceResourceIdentifier.getMutator().addValue(theNewSourceResource, toId(hapiEid));
	}

	/**
	 * Given an Child Definition of `identifier`, a R4/DSTU3 EID Identifier, and a new resource, clone the EID into that resources' identifier list.
	 */
	private void cloneExternalEidIntoNewSourceResource(BaseRuntimeChildDefinition sourceResourceIdentifier, IBase theEid, IBase newSourceResource) {
		// FHIR choice types - fields within fhir where we have a choice of ids
		BaseRuntimeElementCompositeDefinition<?> childIdentifier = (BaseRuntimeElementCompositeDefinition<?>) sourceResourceIdentifier.getChildByName("identifier");
		FhirTerser terser = myFhirContext.newTerser();
		IBase sourceResourceNewIdentifier = childIdentifier.newInstance();
		terser.cloneInto(theEid, sourceResourceNewIdentifier, true);
		sourceResourceIdentifier.getMutator().addValue(newSourceResource, sourceResourceNewIdentifier);
	}

	private void cloneAllExternalEidsIntoNewSourceResource(BaseRuntimeChildDefinition sourceResourceIdentifier, IBase theSourceResource, IBase newSourceResource) {
		// FHIR choice types - fields within fhir where we have a choice of ids
		IFhirPath fhirPath = myFhirContext.newFhirPath();
		List<IBase> sourceResourceIdentifiers = sourceResourceIdentifier.getAccessor().getValues(theSourceResource);

		for (IBase base : sourceResourceIdentifiers) {
			Optional<IPrimitiveType> system = fhirPath.evaluateFirst(base, "system", IPrimitiveType.class);
			if (system.isPresent()) {
				if (system.get().equals(myEmpiConfig.getEmpiRules().getEnterpriseEIDSystem())) {
					cloneExternalEidIntoNewSourceResource(sourceResourceIdentifier, base, newSourceResource);
				}
			}
		}
	}

	private void populateMetaTag(IBaseResource theResource) {
		IBaseCoding tag = theResource.getMeta().addTag();
		tag.setSystem(EmpiConstants.SYSTEM_EMPI_MANAGED);
		tag.setCode(EmpiConstants.CODE_HAPI_EMPI_MANAGED);
		tag.setDisplay(EmpiConstants.DISPLAY_HAPI_EMPI_MANAGED);
	}

	private void validateContextSupported() {
		FhirVersionEnum fhirVersion = myFhirContext.getVersion().getVersion();
		if (fhirVersion == R4 || fhirVersion == DSTU3) {
			return;
		}
		throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
	}

	/**
	 * Update a Person's EID based on the incoming target resource. If the incoming resource has an external EID, it is applied
	 * to the Person, unless that person already has an external EID which does not match, in which case throw {@link IllegalArgumentException}
	 * <p>
	 * If running in multiple EID mode, then incoming EIDs are simply added to the Person without checking for matches.
	 *
	 * @param theSourceResource The person to update the external EID on.
	 * @param theTargetResource The target we will retrieve the external EID from.
	 * @return the modified {@link IBaseResource} representing the person.
	 */
	public IAnyResource updateSourceResourceExternalEidFromTargetResource(IAnyResource theSourceResource, IAnyResource
		theTargetResource, EmpiTransactionContext theEmpiTransactionContext) {
		//This handles overwriting an automatically assigned EID if a patient that links is coming in with an official EID.
		List<CanonicalEID> incomingTargetEid = myEIDHelper.getExternalEid(theTargetResource);
		List<CanonicalEID> personOfficialEid = myEIDHelper.getExternalEid(theSourceResource);

		if (!incomingTargetEid.isEmpty()) {
			if (personOfficialEid.isEmpty() || !myEmpiConfig.isPreventMultipleEids()) {
				log(theEmpiTransactionContext, "Incoming resource:" + theTargetResource.getIdElement().toUnqualifiedVersionless() + " + with EID " + incomingTargetEid.stream().map(CanonicalEID::toString).collect(Collectors.joining(",")) + " is applying this EIDs to its related Source Resource, as this Source Resource does not yet have an external EID");
				addCanonicalEidsToSourceResourceIfAbsent(theSourceResource, incomingTargetEid);
			} else if (!personOfficialEid.isEmpty() && myEIDHelper.eidMatchExists(personOfficialEid, incomingTargetEid)) {
				log(theEmpiTransactionContext, "incoming resource:" + theTargetResource.getIdElement().toVersionless() + " with EIDs " + incomingTargetEid.stream().map(CanonicalEID::toString).collect(Collectors.joining(",")) + " does not need to overwrite person, as this EID is already present");
			} else {
				throw new IllegalArgumentException("This would create a duplicate person!");
			}
		}
		return theSourceResource;
	}

	public IBaseResource overwriteExternalEids(IBaseResource thePerson, List<CanonicalEID> theNewEid) {
		clearExternalEids(thePerson);
		addCanonicalEidsToSourceResourceIfAbsent(thePerson, theNewEid);
		return thePerson;
	}

	private void clearExternalEids(IBaseResource thePerson) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				Person personR4 = (Person) thePerson;
				personR4.getIdentifier().removeIf(theIdentifier -> theIdentifier.getSystem().equalsIgnoreCase(myEmpiConfig.getEmpiRules().getEnterpriseEIDSystem()));
				break;
			case DSTU3:
				org.hl7.fhir.dstu3.model.Person personDstu3 = (org.hl7.fhir.dstu3.model.Person) thePerson;
				personDstu3.getIdentifier().removeIf(theIdentifier -> theIdentifier.getSystem().equalsIgnoreCase(myEmpiConfig.getEmpiRules().getEnterpriseEIDSystem()));
				break;
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	/**
	 * Given a list of incoming External EIDs, and a Source Resource, apply all the EIDs to this resource, which did not already exist on it.
	 */
	private void addCanonicalEidsToSourceResourceIfAbsent(IBaseResource theSourceResource, List<CanonicalEID> theIncomingTargetExternalEids) {
		List<CanonicalEID> sourceResourceExternalEids = myEIDHelper.getExternalEid(theSourceResource);

		for (CanonicalEID incomingExternalEid : theIncomingTargetExternalEids) {
			if (sourceResourceExternalEids.contains(incomingExternalEid)) {
				continue;
			} else {
				// get a ref to the actual ID Field
				RuntimeResourceDefinition resourceDefinition = myFhirContext.getResourceDefinition(theSourceResource);
				// hapi has 2 metamodels: for children and types
				BaseRuntimeChildDefinition sourceResourceIdentifier = resourceDefinition.getChildByName("identifier");
				cloneExternalEidIntoNewSourceResource(sourceResourceIdentifier, toId(incomingExternalEid), theSourceResource);
			}
		}
	}

	private <T> T toId(CanonicalEID eid) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				return (T) eid.toR4();
			case DSTU3:
				return (T) eid.toDSTU3();
		}
		throw new IllegalStateException("Unsupported FHIR version " + myFhirContext.getVersion().getVersion());
	}

	/**
	 * To avoid adding duplicate
	 *
	 * @param thePerson
	 * @param theIdentifier
	 */
	private void addIdentifierIfAbsent(org.hl7.fhir.dstu3.model.Person thePerson, org.hl7.fhir.dstu3.model.Identifier
		theIdentifier) {
		Optional<org.hl7.fhir.dstu3.model.Identifier> first = thePerson.getIdentifier().stream().filter(identifier -> identifier.getSystem().equals(theIdentifier.getSystem())).filter(identifier -> identifier.getValue().equals(theIdentifier.getValue())).findFirst();
		if (first.isPresent()) {
			return;
		} else {
			thePerson.addIdentifier(theIdentifier);
		}
	}

	public void mergePersonFields(IBaseResource theFromPerson, IBaseResource theToPerson) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				mergeR4PersonFields(theFromPerson, theToPerson);
				break;
			case DSTU3:
				mergeDstu3PersonFields(theFromPerson, theToPerson);
				break;
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	private void mergeR4PersonFields(IBaseResource theFromPerson, IBaseResource theToPerson) {
		Person fromPerson = (Person) theFromPerson;
		Person toPerson = (Person) theToPerson;

		mergeElementList(fromPerson, toPerson, HumanName.class, Person::getName, HumanName::equalsDeep);
		mergeElementList(fromPerson, toPerson, Identifier.class, Person::getIdentifier, Identifier::equalsDeep);
		mergeElementList(fromPerson, toPerson, Address.class, Person::getAddress, Address::equalsDeep);
		mergeElementList(fromPerson, toPerson, ContactPoint.class, Person::getTelecom, ContactPoint::equalsDeep);
		if (!toPerson.hasBirthDate()) {
			toPerson.setBirthDate(fromPerson.getBirthDate());
		}
		if (!toPerson.hasGender()) {
			toPerson.setGender(fromPerson.getGender());
		}
		if (!toPerson.hasPhoto()) {
			toPerson.setPhoto(fromPerson.getPhoto());
		}
	}

	private <P, T> void mergeElementList(P fromPerson, P
		toPerson, Class<T> theBase, Function<P, List<T>> theGetList, BiPredicate<T, T> theEquals) {
		List<T> fromList = theGetList.apply(fromPerson);
		List<T> toList = theGetList.apply(toPerson);
		List<T> itemsToAdd = new ArrayList<>();

		for (T fromItem : fromList) {
			if (toList.stream().noneMatch(t -> theEquals.test(fromItem, t))) {
				itemsToAdd.add(fromItem);
			}
		}
		toList.addAll(itemsToAdd);
	}

	private void mergeDstu3PersonFields(IBaseResource theFromPerson, IBaseResource theToPerson) {
		org.hl7.fhir.dstu3.model.Person fromPerson = (org.hl7.fhir.dstu3.model.Person) theFromPerson;
		org.hl7.fhir.dstu3.model.Person toPerson = (org.hl7.fhir.dstu3.model.Person) theToPerson;

		mergeElementList(fromPerson, toPerson, org.hl7.fhir.dstu3.model.HumanName.class, org.hl7.fhir.dstu3.model.Person::getName, org.hl7.fhir.dstu3.model.HumanName::equalsDeep);
		mergeElementList(fromPerson, toPerson, org.hl7.fhir.dstu3.model.Identifier.class, org.hl7.fhir.dstu3.model.Person::getIdentifier, org.hl7.fhir.dstu3.model.Identifier::equalsDeep);
		mergeElementList(fromPerson, toPerson, org.hl7.fhir.dstu3.model.Address.class, org.hl7.fhir.dstu3.model.Person::getAddress, org.hl7.fhir.dstu3.model.Address::equalsDeep);
		mergeElementList(fromPerson, toPerson, org.hl7.fhir.dstu3.model.ContactPoint.class, org.hl7.fhir.dstu3.model.Person::getTelecom, org.hl7.fhir.dstu3.model.ContactPoint::equalsDeep);

		if (!toPerson.hasBirthDate()) {
			toPerson.setBirthDate(fromPerson.getBirthDate());
		}
		if (!toPerson.hasGender()) {
			toPerson.setGender(fromPerson.getGender());
		}
		if (!toPerson.hasPhoto()) {
			toPerson.setPhoto(fromPerson.getPhoto());
		}
	}

	/**
	 * An incoming resource is a potential duplicate if it matches a Patient that has a Person with an official EID, but
	 * the incoming resource also has an EID that does not match.
	 *
	 * @param theExistingPerson
	 * @param theComparingPerson
	 * @return
	 */
	public boolean isPotentialDuplicate(IAnyResource theExistingPerson, IAnyResource theComparingPerson) {
		List<CanonicalEID> externalEidsPerson = myEIDHelper.getExternalEid(theExistingPerson);
		List<CanonicalEID> externalEidsResource = myEIDHelper.getExternalEid(theComparingPerson);
		return !externalEidsPerson.isEmpty() && !externalEidsResource.isEmpty() && !myEIDHelper.eidMatchExists(externalEidsResource, externalEidsPerson);
	}

	public IBaseBackboneElement newPersonLink(IIdType theTargetId, CanonicalIdentityAssuranceLevel theAssuranceLevel) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				return newR4PersonLink(theTargetId, theAssuranceLevel);
			case DSTU3:
				return newDstu3PersonLink(theTargetId, theAssuranceLevel);
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	private IBaseBackboneElement newR4PersonLink(IIdType theTargetId, CanonicalIdentityAssuranceLevel
		theAssuranceLevel) {
		Person.PersonLinkComponent retval = new Person.PersonLinkComponent();
		retval.setTarget(new Reference(theTargetId));
		retval.setAssurance(theAssuranceLevel.toR4());
		return retval;
	}

	private IBaseBackboneElement newDstu3PersonLink(IIdType theTargetId, CanonicalIdentityAssuranceLevel
		theAssuranceLevel) {
		org.hl7.fhir.dstu3.model.Person.PersonLinkComponent retval = new org.hl7.fhir.dstu3.model.Person.PersonLinkComponent();
		retval.setTarget(new org.hl7.fhir.dstu3.model.Reference(theTargetId));
		retval.setAssurance(theAssuranceLevel.toDstu3());
		return retval;
	}

	public void setLinks(IAnyResource thePersonResource, List<IBaseBackboneElement> theNewLinks) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				setLinksR4(thePersonResource, theNewLinks);
				break;
			case DSTU3:
				setLinksDstu3(thePersonResource, theNewLinks);
				break;
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	private void setLinksDstu3(IAnyResource thePersonResource, List<IBaseBackboneElement> theLinks) {
		org.hl7.fhir.dstu3.model.Person person = (org.hl7.fhir.dstu3.model.Person) thePersonResource;
		List<org.hl7.fhir.dstu3.model.Person.PersonLinkComponent> links = (List<org.hl7.fhir.dstu3.model.Person.PersonLinkComponent>) (List<?>) theLinks;
		person.setLink(links);
	}

	private void setLinksR4(IAnyResource thePersonResource, List<IBaseBackboneElement> theLinks) {
		Person person = (Person) thePersonResource;
		List<Person.PersonLinkComponent> links = (List<Person.PersonLinkComponent>) (List<?>) theLinks;
		person.setLink(links);
	}

	public int getLinkCount(IAnyResource thePerson) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				Person personR4 = (Person) thePerson;
				return personR4.getLink().size();
			case DSTU3:
				org.hl7.fhir.dstu3.model.Person personStu3 = (org.hl7.fhir.dstu3.model.Person) thePerson;
				return personStu3.getLink().size();
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	private void log(EmpiTransactionContext theEmpiTransactionContext, String theMessage) {
		theEmpiTransactionContext.addTransactionLogMessage(theMessage);
		ourLog.debug(theMessage);
	}

	public void handleExternalEidAddition(IAnyResource theSourceResource, IAnyResource theTargetResource, EmpiTransactionContext
		theEmpiTransactionContext) {
		List<CanonicalEID> eidFromResource = myEIDHelper.getExternalEid(theTargetResource);
		if (!eidFromResource.isEmpty()) {
			updateSourceResourceExternalEidFromTargetResource(theSourceResource, theTargetResource, theEmpiTransactionContext);
		}
	}

	public void deactivatePerson(IAnyResource thePerson) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				Person personR4 = (Person) thePerson;
				personR4.setActive(false);
				break;
			case DSTU3:
				org.hl7.fhir.dstu3.model.Person personStu3 = (org.hl7.fhir.dstu3.model.Person) thePerson;
				personStu3.setActive(false);
				break;
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}

	public boolean isDeactivated(IBaseResource thePerson) {
		switch (myFhirContext.getVersion().getVersion()) {
			case R4:
				Person personR4 = (Person) thePerson;
				return !personR4.getActive();
			case DSTU3:
				org.hl7.fhir.dstu3.model.Person personStu3 = (org.hl7.fhir.dstu3.model.Person) thePerson;
				return !personStu3.getActive();
			default:
				throw new UnsupportedOperationException("Version not supported: " + myFhirContext.getVersion().getVersion());
		}
	}
}
