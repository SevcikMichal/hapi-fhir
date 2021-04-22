package ca.uhn.fhir.rest.server.interceptor.validation.fields;

/*-
 * #%L
 * HAPI FHIR - Server Framework
 * %%
 * Copyright (C) 2014 - 2021 Smile CDR, Inc.
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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.ConfigLoader;
import ca.uhn.fhir.util.ExtensionUtil;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Interceptor
public class FieldValidatingInterceptor {

	public enum ValidatorType {
		EMAIL;
	}

	private static final Logger ourLog = LoggerFactory.getLogger(FieldValidatingInterceptor.class);

	public static final String VALIDATION_DISABLED_HEADER = "HAPI-Field-Validation-Disabled";
	public static final String VALIDATION_EXTENSION_URL = "https://hapifhir.org/StructureDefinition/ext-validation-field-error";
	public static final String PROPERTY_EXTENSION_URL = "validation.extension.url";

	private Map<String, String> myConfig;

	public FieldValidatingInterceptor() {
		super();

		ourLog.info("Starting FieldValidatingInterceptor {}", this);
		myConfig = ConfigLoader.loadJson("classpath:field-validation-rules.json", Map.class);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void resourcePreCreate(RequestDetails theRequest, IBaseResource theResource) {
		ourLog.debug("Validating address on create for resource {} / request {}", theResource, theRequest);
		handleRequest(theRequest, theResource);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void resourcePreUpdate(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {
		ourLog.debug("Validating address on update for resource {} / old resource {} / request {}", theOldResource, theNewResource, theRequest);
		handleRequest(theRequest, theNewResource);
	}

	protected void handleRequest(RequestDetails theRequest, IBaseResource theResource) {
		if (theRequest == null) {
			ourLog.debug("RequestDetails is null - unable to validate {}", theResource);
			return;
		}

		if (!theRequest.getHeaders(VALIDATION_DISABLED_HEADER).isEmpty()) {
			ourLog.debug("Address validation is disabled for this request via header");
			return;
		}

		FhirContext ctx = theRequest.getFhirContext();
		IFhirPath fhirPath = ctx.newFhirPath();

		for (Map.Entry<String, String> e : myConfig.entrySet()) {
			IValidator validator = getValidator(e.getValue());
			if (validator == null) {
				continue;
			}

			List<IBase> fields = fhirPath.evaluate(theResource, e.getKey(), IBase.class);
			for (IBase field : fields) {

				List<IPrimitiveType> values = fhirPath.evaluate(field, "value", IPrimitiveType.class);
				boolean isValid = true;
				for (IPrimitiveType value : values) {
					String valueAsString = value.getValueAsString();
					isValid = validator.isValid(valueAsString);
					ourLog.debug("Field {} at path {} validated {}", value, e.getKey(), isValid);
					if (!isValid) {
						break;
					}
				}
				setValidationStatus(ctx, field, isValid);
			}
		}
	}

	private void setValidationStatus(FhirContext ctx, IBase theBase, boolean isValid) {
		if (isValid) {
			ExtensionUtil.clearExtensionsByUrl(theBase, getValidationExtensionUrl());
		} else {
			IBaseExtension<?, ?> validationResultExtension = ExtensionUtil.addExtension(theBase, getValidationExtensionUrl());
			ExtensionUtil.setExtension(ctx, theBase, getValidationExtensionUrl(), "boolean", Boolean.TRUE);
		}
	}

	private String getValidationExtensionUrl() {
		if (myConfig.containsKey(PROPERTY_EXTENSION_URL)) {
			return myConfig.get(PROPERTY_EXTENSION_URL);
		}
		return VALIDATION_EXTENSION_URL;
	}

	private IValidator getValidator(String theValue) {
		if (PROPERTY_EXTENSION_URL.equals(theValue)) {
			return null;
		}

		if (ValidatorType.EMAIL.name().equals(theValue)) {
			return new EmailValidator();
		}

		try {
			return (IValidator) Class.forName(theValue).getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			throw new IllegalStateException(String.format("Unable to create validator for %s", theValue), e);
		}
	}

	public Map<String, String> getConfig() {
		return myConfig;
	}

	public void setConfig(Map<String, String> theConfig) {
		myConfig = theConfig;
	}
}
