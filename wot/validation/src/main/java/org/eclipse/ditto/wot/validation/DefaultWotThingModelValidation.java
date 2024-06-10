/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.wot.validation;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.json.JsonKey;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.things.model.Attributes;
import org.eclipse.ditto.things.model.Feature;
import org.eclipse.ditto.things.model.FeatureProperties;
import org.eclipse.ditto.things.model.Features;
import org.eclipse.ditto.wot.model.DittoWotExtension;
import org.eclipse.ditto.wot.model.Properties;
import org.eclipse.ditto.wot.model.Property;
import org.eclipse.ditto.wot.model.SingleUriAtContext;
import org.eclipse.ditto.wot.model.ThingModel;
import org.eclipse.ditto.wot.model.TmOptionalElement;
import org.eclipse.ditto.wot.validation.config.TmValidationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.schema.output.OutputUnit;

/**
 * Default implementation for WoT ThingModel based validation/enforcement.
 */
final class DefaultWotThingModelValidation implements WotThingModelValidation {

    private static final String ATTRIBUTES = "attributes";
    private static final String FEATURES = "features";
    private static final String PROPERTIES = "properties";
    private static final String DESIRED_PROPERTIES = "desiredProperties";

    private static final Logger log = LoggerFactory.getLogger(DefaultWotThingModelValidation.class);

    private final TmValidationConfig validationConfig;
    private final JsonSchemaTools jsonSchemaTools;

    public DefaultWotThingModelValidation(final TmValidationConfig validationConfig) {
        this.validationConfig = validationConfig;
        jsonSchemaTools = new JsonSchemaTools();
    }

    @Override
    public CompletionStage<Void> validateThingAttributes(final ThingModel thingModel,
            @Nullable final Attributes attributes,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        if (validationConfig.getThingValidationConfig().isEnforceAttributes() && attributes != null) {
            return enforceThingAttributes(thingModel, attributes, resourcePath, dittoHeaders);
        }
        return success();
    }

    @Override
    public CompletionStage<Void> validateThingAttribute(final ThingModel thingModel,
            final JsonPointer attributePointer,
            final JsonValue attributeValue,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        if (validationConfig.getThingValidationConfig().isEnforceAttributes()) {
            return enforceThingAttribute(thingModel, attributePointer, attributeValue, resourcePath, dittoHeaders);
        }
        return success();
    }

    @Override
    public CompletionStage<Void> validateFeaturesPresence(final Map<String, ThingModel> featureThingModels,
            @Nullable final Features features,
            final DittoHeaders dittoHeaders
    ) {
        final Set<String> definedFeatureIds = featureThingModels.keySet();
        final Set<String> existingFeatures = Optional.ofNullable(features)
                .map(Features::stream)
                .orElseGet(Stream::empty)
                .map(Feature::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final CompletableFuture<Void> firstStage;
        if (validationConfig.getFeatureValidationConfig().isEnforcePresenceOfModeledFeatures()) {
            if (!existingFeatures.containsAll(definedFeatureIds)) {
                final LinkedHashSet<String> missingFeatureIds = new LinkedHashSet<>(definedFeatureIds);
                missingFeatureIds.removeAll(existingFeatures);
                final var exceptionBuilder = WotThingModelPayloadValidationException
                        .newBuilder("Attempting to update the Thing with missing in the model " +
                                "defined features: " + missingFeatureIds);
                firstStage = CompletableFuture.failedFuture(exceptionBuilder
                        .dittoHeaders(dittoHeaders)
                        .build());
            } else {
                firstStage = success();
            }
        } else {
            firstStage = success();
        }

        final CompletableFuture<Void> secondStage;
        if (!validationConfig.getFeatureValidationConfig().isAllowNonModeledFeatures()) {
            final LinkedHashSet<String> extraFeatureIds = new LinkedHashSet<>(existingFeatures);
            extraFeatureIds.removeAll(definedFeatureIds);
            if (!extraFeatureIds.isEmpty()) {
                final var exceptionBuilder = WotThingModelPayloadValidationException
                        .newBuilder("Attempting to update the Thing with feature(s) are were not " +
                                "defined in the model: " + extraFeatureIds);
                secondStage = CompletableFuture.failedFuture(exceptionBuilder
                        .dittoHeaders(dittoHeaders)
                        .build());
            } else {
                secondStage = success();
            }
        } else {
            secondStage = success();
        }
        return firstStage.thenCompose(unused -> secondStage);
    }

    @Override
    public CompletionStage<Void> validateFeaturesProperties(final Map<String, ThingModel> featureThingModels,
            final @Nullable Features features,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        final CompletableFuture<List<Void>> enforcedPropertiesListFuture;
        if (validationConfig.getFeatureValidationConfig().isEnforceProperties() && features != null) {
            final List<CompletableFuture<Void>> enforcedPropertiesFutures = featureThingModels
                    .entrySet()
                    .stream()
                    .filter(entry -> features.getFeature(entry.getKey()).isPresent())
                    .map(entry ->
                            enforceFeatureProperties(entry.getValue(),
                                    features.getFeature(entry.getKey()).orElseThrow(),
                                    false,
                                    validationConfig.getFeatureValidationConfig().isAllowNonModeledProperties(),
                                    resourcePath,
                                    dittoHeaders
                            )
                    )
                    .toList();
            enforcedPropertiesListFuture =
                    CompletableFuture.allOf(enforcedPropertiesFutures.toArray(new CompletableFuture[0]))
                            .thenApply(ignored -> enforcedPropertiesFutures.stream()
                                    .map(CompletableFuture::join)
                                    .toList()
                            );
        } else {
            enforcedPropertiesListFuture = success();
        }

        if (validationConfig.getFeatureValidationConfig().isEnforceDesiredProperties() && features != null) {
            final List<CompletableFuture<Void>> enforcedDesiredPropertiesFutures = featureThingModels
                    .entrySet()
                    .stream()
                    .map(entry -> enforceFeatureProperties(entry.getValue(),
                                    features.getFeature(entry.getKey()).orElseThrow(),
                                    true,
                                    validationConfig.getFeatureValidationConfig().isAllowNonModeledDesiredProperties(),
                                    resourcePath,
                                    dittoHeaders
                            )
                    )
                    .toList();
            return enforcedPropertiesListFuture.thenCompose(voidL ->
                    CompletableFuture.allOf(enforcedDesiredPropertiesFutures.toArray(new CompletableFuture[0]))
                            .thenApply(ignored -> enforcedDesiredPropertiesFutures.stream()
                                    .map(CompletableFuture::join)
                                    .toList()
                            )
            ).thenApply(voidL -> null);
        }
        return enforcedPropertiesListFuture.thenApply(voidL -> null);
    }

    @Override
    public CompletionStage<Void> validateFeaturePresence(final Map<String, ThingModel> featureThingModels,
            final Feature feature,
            final DittoHeaders dittoHeaders
    ) {
        final Set<String> definedFeatureIds = featureThingModels.keySet();
        final String featureId = feature.getId();

        final CompletableFuture<Void> stage;
        if (!validationConfig.getFeatureValidationConfig().isAllowNonModeledFeatures()) {
            if (!definedFeatureIds.contains(featureId)) {
                final var exceptionBuilder = WotThingModelPayloadValidationException
                        .newBuilder("Attempting to update the Thing with a feature which is not " +
                                "defined in the model: <" + featureId + ">");
                stage = CompletableFuture.failedFuture(exceptionBuilder
                        .dittoHeaders(dittoHeaders)
                        .build());
            } else {
                stage = success();
            }
        } else {
            stage = success();
        }
        return stage;
    }

    @Override
    public CompletionStage<Void> validateFeature(final ThingModel featureThingModel,
            final Feature feature,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        final CompletableFuture<Void> enforcedPropertiesFuture;
        if (validationConfig.getFeatureValidationConfig().isEnforceProperties()) {
            enforcedPropertiesFuture = enforceFeatureProperties(featureThingModel,
                    feature,
                    false,
                    validationConfig.getFeatureValidationConfig().isAllowNonModeledProperties(),
                    resourcePath,
                    dittoHeaders
            );
        } else {
            enforcedPropertiesFuture = success();
        }

        if (validationConfig.getFeatureValidationConfig().isEnforceDesiredProperties()) {
            return enforcedPropertiesFuture.thenCompose(aVoid ->
                    enforceFeatureProperties(featureThingModel,
                            feature,
                            true,
                            validationConfig.getFeatureValidationConfig().isAllowNonModeledDesiredProperties(),
                            resourcePath,
                            dittoHeaders
                    )
            );
        }
        return enforcedPropertiesFuture;
    }

    @Override
    public CompletionStage<Void> validateFeatureProperties(final ThingModel featureThingModel,
            final String featureId,
            @Nullable final FeatureProperties properties,
            final boolean desiredProperties,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        if (validationConfig.getFeatureValidationConfig().isEnforceProperties()) {
            return enforceFeatureProperties(featureThingModel,
                    desiredProperties ?
                            Feature.newBuilder().desiredProperties(properties).withId(featureId).build() :
                            Feature.newBuilder().properties(properties).withId(featureId).build(),
                    desiredProperties,
                    desiredProperties ?
                            validationConfig.getFeatureValidationConfig().isAllowNonModeledDesiredProperties() :
                            validationConfig.getFeatureValidationConfig().isAllowNonModeledProperties(),
                    resourcePath,
                    dittoHeaders
            );
        }
        return success();
    }

    @Override
    public CompletionStage<Void> validateFeatureProperty(final ThingModel featureThingModel,
            final String featureId,
            final JsonPointer propertyPointer,
            final JsonValue propertyValue,
            final boolean desiredProperty,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        if (validationConfig.getFeatureValidationConfig().isEnforceProperties()) {
            return enforceFeatureProperty(featureThingModel,
                    featureId,
                    propertyPointer,
                    propertyValue,
                    desiredProperty,
                    desiredProperty ?
                            validationConfig.getFeatureValidationConfig().isAllowNonModeledDesiredProperties() :
                            validationConfig.getFeatureValidationConfig().isAllowNonModeledProperties(),
                    resourcePath,
                    dittoHeaders
            );
        }
        return success();
    }

    private CompletableFuture<Void> enforceThingAttributes(final ThingModel thingModel,
            final Attributes attributes,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {

        final JsonPointer attributesPointer = JsonPointer.of(ATTRIBUTES);
        final JsonPointer pathPrefix = attributesPointer.equals(resourcePath) ? JsonPointer.empty() : attributesPointer;

        return thingModel.getProperties()
                .map(tdProperties -> {
                    final String containerNamePlural = "Thing's attributes";
                    final CompletableFuture<Void> ensureRequiredPropertiesStage =
                            ensureRequiredProperties(thingModel, dittoHeaders, tdProperties, attributes,
                                    containerNamePlural, "Thing's attribute",
                                    pathPrefix, false);

                    final CompletableFuture<Void> ensureOnlyDefinedPropertiesStage;
                    if (!validationConfig.getThingValidationConfig().isAllowNonModeledAttributes()) {
                        ensureOnlyDefinedPropertiesStage = ensureOnlyDefinedProperties(thingModel, dittoHeaders,
                                tdProperties, attributes, containerNamePlural, false);
                    } else {
                        ensureOnlyDefinedPropertiesStage = success();
                    }

                    final CompletableFuture<Void> validatePropertiesStage =
                            getValidatePropertiesStage(thingModel, dittoHeaders, tdProperties, attributes,
                                    true, containerNamePlural, pathPrefix, false);

                    return CompletableFuture.allOf(
                            ensureRequiredPropertiesStage,
                            ensureOnlyDefinedPropertiesStage,
                            validatePropertiesStage
                    );
                }).orElseGet(DefaultWotThingModelValidation::success);
    }

    private CompletableFuture<Void> enforceThingAttribute(final ThingModel thingModel,
            final JsonPointer attributePath,
            final JsonValue attributeValue,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {

        return thingModel.getProperties()
                .map(tdProperties -> {
                    final Attributes attributes = Attributes.newBuilder().set(attributePath, attributeValue).build();
                    final CompletableFuture<Void> ensureOnlyDefinedPropertiesStage;
                    if (!validationConfig.getFeatureValidationConfig().isAllowNonModeledProperties()) {
                        ensureOnlyDefinedPropertiesStage = ensureOnlyDefinedProperties(thingModel, dittoHeaders,
                                tdProperties, attributes, "Thing's attributes", false);
                    } else {
                        ensureOnlyDefinedPropertiesStage = success();
                    }

                    final CompletableFuture<Void> validatePropertiesStage =
                            getValidatePropertyStage(thingModel, dittoHeaders, tdProperties, attributePath,
                                    true, attributeValue,
                                    "Thing's attribute <" + attributePath + ">", resourcePath, false
                            );

                    return CompletableFuture.allOf(
                            ensureOnlyDefinedPropertiesStage,
                            validatePropertiesStage
                    );
                }).orElseGet(DefaultWotThingModelValidation::success);
    }

    private CompletableFuture<Void> ensureRequiredProperties(final ThingModel thingModel,
            final DittoHeaders dittoHeaders,
            final Properties tdProperties,
            final JsonObject propertiesContainer,
            final String containerNamePlural,
            final String containerName,
            final JsonPointer pointerPrefix,
            final boolean handleDittoCategory
    ) {
        final Map<String, Property> nonProvidedRequiredProperties =
                filterNonProvidedRequiredProperties(tdProperties, thingModel, propertiesContainer, handleDittoCategory);

        final CompletableFuture<Void> requiredPropertiesStage;
        if (!nonProvidedRequiredProperties.isEmpty()) {
            final var exceptionBuilder = WotThingModelPayloadValidationException
                    .newBuilder("Required JSON fields were missing from the " + containerNamePlural);
            nonProvidedRequiredProperties.forEach((rpKey, requiredProperty) ->
                    {
                        JsonPointer fullPointer = pointerPrefix;
                        final Optional<String> dittoCategory = determineDittoCategory(thingModel, requiredProperty);
                        if (handleDittoCategory && dittoCategory.isPresent()) {
                            fullPointer = fullPointer.addLeaf(JsonKey.of(dittoCategory.get()));
                        }
                        fullPointer = fullPointer.addLeaf(JsonKey.of(rpKey));
                        exceptionBuilder.addValidationDetail(
                                fullPointer,
                                List.of(containerName + " <" + rpKey + "> is non optional and must be provided")
                        );
                    }
            );
            requiredPropertiesStage = CompletableFuture
                    .failedFuture(exceptionBuilder.dittoHeaders(dittoHeaders).build());
        } else {
            requiredPropertiesStage = success();
        }
        return requiredPropertiesStage;
    }

    private static Optional<String> determineDittoCategory(final ThingModel thingModel, final Property property) {
        final Optional<String> dittoExtensionPrefix = thingModel.getAtContext()
                .determinePrefixFor(SingleUriAtContext.DITTO_WOT_EXTENSION);
        return dittoExtensionPrefix.flatMap(prefix ->
                        property.getValue(prefix + ":" + DittoWotExtension.DITTO_WOT_EXTENSION_CATEGORY)
                )
                .filter(JsonValue::isString)
                .map(JsonValue::asString);
    }

    private Map<String, Property> filterNonProvidedRequiredProperties(final Properties tdProperties,
            final ThingModel thingModel,
            final JsonObject propertiesContainer,
            final boolean handleDittoCategory
    ) {
        final Map<String, Property> requiredProperties = extractRequiredProperties(tdProperties, thingModel);
        final Map<String, Property> nonProvidedRequiredProperties = new LinkedHashMap<>(requiredProperties);
        if (handleDittoCategory) {
            requiredProperties.forEach((rpKey, requiredProperty) -> {
                final Optional<String> dittoCategory = determineDittoCategory(thingModel, requiredProperty);
                if (dittoCategory.isPresent()) {
                    propertiesContainer.getValue(dittoCategory.get())
                            .filter(JsonValue::isObject)
                            .map(JsonValue::asObject)
                            .ifPresent(categorizedProperties -> categorizedProperties.getKeys().stream()
                                    .map(JsonKey::toString)
                                    .forEach(nonProvidedRequiredProperties::remove)
                            );
                } else {
                    propertiesContainer.getKeys().stream()
                            .map(JsonKey::toString)
                            .forEach(nonProvidedRequiredProperties::remove);
                }
            });
        } else {
            propertiesContainer.getKeys().stream()
                    .map(JsonKey::toString)
                    .forEach(nonProvidedRequiredProperties::remove);
        }
        return nonProvidedRequiredProperties;
    }

    private CompletableFuture<Void> ensureOnlyDefinedProperties(final ThingModel thingModel,
            final DittoHeaders dittoHeaders,
            final Properties tdProperties,
            final JsonObject propertiesContainer,
            final String containerNamePlural,
            final boolean handleDittoCategory
    ) {
        final Set<String> allDefinedPropertyKeys = tdProperties.keySet();
        final Set<String> allAvailablePropertiesKeys =
                propertiesContainer.getKeys().stream().map(JsonKey::toString)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (handleDittoCategory) {
            tdProperties.forEach((propertyName, property) -> {
                final Optional<String> dittoCategory = determineDittoCategory(thingModel, property);
                final String categorizedPropertyName = dittoCategory
                        .map(c -> c + "/").orElse("")
                        .concat(propertyName);
                if (propertiesContainer.contains(JsonPointer.of(categorizedPropertyName))) {
                    allAvailablePropertiesKeys.remove(propertyName);
                    dittoCategory.ifPresent(allAvailablePropertiesKeys::remove);
                }
            });
        } else {
            allAvailablePropertiesKeys.removeAll(allDefinedPropertyKeys);
        }

        if (!allAvailablePropertiesKeys.isEmpty()) {
            final var exceptionBuilder = WotThingModelPayloadValidationException
                    .newBuilder("The " + containerNamePlural + " contained " +
                            "JSON fields which were not defined in the model: " + allAvailablePropertiesKeys);
            return CompletableFuture.failedFuture(exceptionBuilder
                    .dittoHeaders(dittoHeaders)
                    .build());
        }
        return success();
    }

    private CompletableFuture<Void> getValidatePropertiesStage(final ThingModel thingModel,
            final DittoHeaders dittoHeaders,
            final Properties tdProperties,
            final JsonObject propertiesContainer,
            final boolean validateRequiredObjectFields,
            final String containerNamePlural,
            final JsonPointer pointerPrefix,
            final boolean handleDittoCategory
    ) {
        final CompletableFuture<Void> validatePropertiesStage;
        final Map<Property, OutputUnit> invalidProperties;
        if (handleDittoCategory) {
            invalidProperties = determineInvalidProperties(tdProperties,
                    p -> propertiesContainer.getValue(
                            determineDittoCategory(thingModel, p)
                                    .map(c -> c + "/")
                                    .orElse("")
                                    .concat(p.getPropertyName())
                    ),
                    validateRequiredObjectFields,
                    dittoHeaders
            );
        } else {
            invalidProperties = determineInvalidProperties(tdProperties,
                    p -> propertiesContainer.getValue(p.getPropertyName()),
                    validateRequiredObjectFields,
                    dittoHeaders
            );
        }

        if (!invalidProperties.isEmpty()) {
            final var exceptionBuilder = WotThingModelPayloadValidationException
                    .newBuilder("The " + containerNamePlural + " contained validation errors, " +
                            "check the validation details.");
            invalidProperties.forEach((property, validationOutputUnit) -> {
                JsonPointer fullPointer = pointerPrefix;
                final Optional<String> dittoCategory = determineDittoCategory(thingModel, property);
                if (handleDittoCategory && dittoCategory.isPresent()) {
                    fullPointer = fullPointer.addLeaf(JsonKey.of(dittoCategory.get()));
                }
                fullPointer = fullPointer.addLeaf(JsonKey.of(property.getPropertyName()));
                exceptionBuilder.addValidationDetail(
                        fullPointer,
                        validationOutputUnit.getDetails().stream()
                                .map(ou -> ou.getInstanceLocation() + ": " + ou.getErrors())
                                .toList()
                );
            });
            validatePropertiesStage = CompletableFuture.failedFuture(exceptionBuilder
                    .dittoHeaders(dittoHeaders)
                    .build());
        } else {
            validatePropertiesStage = success();
        }
        return validatePropertiesStage;
    }

    private CompletableFuture<Void> getValidatePropertyStage(final ThingModel thingModel,
            final DittoHeaders dittoHeaders,
            final Properties tdProperties,
            final JsonPointer propertyPath,
            final boolean validateRequiredObjectFields,
            final JsonValue propertyValue,
            final String propertyDescription,
            final JsonPointer resourcePath,
            final boolean handleDittoCategory
    ) {
        final JsonValue valueToValidate;
        if (propertyPath.getLevelCount() > 1) {
            valueToValidate = JsonObject.newBuilder()
                    .set(propertyPath.getSubPointer(1).orElseThrow(), propertyValue)
                    .build();
        } else {
            valueToValidate = propertyValue;
        }

        final Optional<OutputUnit> validationOutput = tdProperties
                .values().stream()
                .filter(property -> {
                    if (handleDittoCategory) {
                        final JsonPointer thePropertyPath = determineDittoCategory(thingModel, property)
                                .flatMap(cat -> propertyPath.getSubPointer(1))
                                .orElse(propertyPath);
                        return property.getPropertyName().equals(thePropertyPath.getRoot().orElseThrow().toString());
                    } else {
                        return property.getPropertyName().equals(propertyPath.getRoot().orElseThrow().toString());
                    }
                })
                .findFirst()
                .map(property -> {
                    if (handleDittoCategory) {
                        final Optional<String> dittoCategory = determineDittoCategory(thingModel, property);
                        final JsonPointer thePropertyPath = dittoCategory
                                .flatMap(cat -> propertyPath.getSubPointer(1))
                                .orElse(propertyPath);
                        final JsonValue theValueToValidate = dittoCategory
                                .flatMap(cat -> valueToValidate.asObject().getValue(thePropertyPath))
                                .orElse(valueToValidate);
                        return jsonSchemaTools.validateDittoJsonBasedOnDataSchema(
                                property,
                                thePropertyPath,
                                validateRequiredObjectFields,
                                theValueToValidate,
                                dittoHeaders
                        );
                    } else {
                        return jsonSchemaTools.validateDittoJsonBasedOnDataSchema(
                                property,
                                propertyPath,
                                validateRequiredObjectFields,
                                valueToValidate,
                                dittoHeaders
                        );
                    }
                })
                .filter(outputUnit -> !outputUnit.isValid());

        final CompletableFuture<Void> validatePropertiesStage;
        if (validationOutput.isPresent()) {
            final var exceptionBuilder = WotThingModelPayloadValidationException
                    .newBuilder("The " + propertyDescription + " contained validation errors, " +
                            "check the validation details.");
            exceptionBuilder.addValidationDetail(
                    JsonPointer.empty(),
                    validationOutput.get().getDetails().stream()
                            .map(ou -> ou.getInstanceLocation() + ": " + ou.getErrors())
                            .toList()
            );
            validatePropertiesStage = CompletableFuture.failedFuture(exceptionBuilder
                    .dittoHeaders(dittoHeaders)
                    .build());
        } else {
            validatePropertiesStage = success();
        }
        return validatePropertiesStage;
    }

    private Map<Property, OutputUnit> determineInvalidProperties(final Properties tdProperties,
            final Function<Property, Optional<JsonValue>> propertyExtractor,
            final boolean validateRequiredObjectFields,
            final DittoHeaders dittoHeaders
    ) {
        return tdProperties.entrySet().stream()
                .flatMap(tdPropertyEntry ->
                        propertyExtractor.apply(tdPropertyEntry.getValue())
                                .map(propertyValue -> new AbstractMap.SimpleEntry<>(
                                        tdPropertyEntry.getValue(),
                                        jsonSchemaTools.validateDittoJsonBasedOnDataSchema(
                                                tdPropertyEntry.getValue(),
                                                JsonPointer.empty(),
                                                validateRequiredObjectFields,
                                                propertyValue,
                                                dittoHeaders
                                        )
                                ))
                                .filter(entry -> !entry.getValue().isValid())
                                .stream()
                ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private CompletableFuture<Void> enforceFeatureProperties(final ThingModel featureThingModel,
            final Feature feature,
            final boolean desiredProperties,
            final boolean allowNonModeledProperties,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        final JsonPointer featuresPointer = JsonPointer.of(FEATURES);
        final JsonPointer pathPrefix = featuresPointer.equals(resourcePath) ? JsonPointer.of(feature.getId()) :
                featuresPointer.addLeaf(JsonKey.of(feature.getId()));

        return featureThingModel.getProperties()
                .map(tdProperties -> {
                    final FeatureProperties featureProperties;
                    if (desiredProperties) {
                        featureProperties = feature.getDesiredProperties()
                                .orElseGet(() -> FeatureProperties.newBuilder().build());
                    } else {
                        featureProperties = feature.getProperties()
                                .orElseGet(() -> FeatureProperties.newBuilder().build());
                    }

                    final String containerNamePrefix = "Feature <" + feature.getId() + ">'s " +
                            (desiredProperties ? "desired " : "");
                    final String containerNamePlural = containerNamePrefix + "properties";
                    final JsonPointer path = desiredProperties ? pathPrefix.addLeaf(JsonKey.of(DESIRED_PROPERTIES)) :
                            pathPrefix.addLeaf(JsonKey.of(PROPERTIES));

                    final CompletableFuture<Void> ensureRequiredPropertiesStage;
                    if (!desiredProperties) {
                        ensureRequiredPropertiesStage = ensureRequiredProperties(featureThingModel, dittoHeaders,
                                tdProperties, featureProperties, containerNamePlural,
                                containerNamePrefix + "property", JsonPointer.of(path), true);
                    } else {
                        ensureRequiredPropertiesStage = success();
                    }

                    final CompletableFuture<Void> ensureOnlyDefinedPropertiesStage;
                    if (!allowNonModeledProperties) {
                        ensureOnlyDefinedPropertiesStage = ensureOnlyDefinedProperties(featureThingModel, dittoHeaders,
                                tdProperties, featureProperties, containerNamePlural, true);
                    } else {
                        ensureOnlyDefinedPropertiesStage = success();
                    }

                    final CompletableFuture<Void> validatePropertiesStage =
                            getValidatePropertiesStage(featureThingModel, dittoHeaders, tdProperties, featureProperties,
                                    !desiredProperties, containerNamePlural, JsonPointer.of(path), true);

                    return CompletableFuture.allOf(
                            ensureRequiredPropertiesStage,
                            ensureOnlyDefinedPropertiesStage,
                            validatePropertiesStage
                    );
                }).orElseGet(DefaultWotThingModelValidation::success);
    }

    private CompletableFuture<Void> enforceFeatureProperty(final ThingModel featureThingModel,
            final String featureId,
            final JsonPointer propertyPath,
            final JsonValue propertyValue,
            final boolean desiredProperty,
            final boolean allowNonModeledProperties,
            final JsonPointer resourcePath,
            final DittoHeaders dittoHeaders
    ) {
        return featureThingModel.getProperties()
                .map(tdProperties -> {
                    final String containerNamePrefix = "Feature <" + featureId + ">'s " +
                            (desiredProperty ? "desired " : "");
                    final String containerNamePlural = containerNamePrefix + "properties";

                    // TODO TJ when updating a ditto:category (e.g. PUT /status), we also need to check that all of the
                    //  non-optional properties are present in that request
                    //  maybe we even need a separate "enforceFeaturePropertiesCategory" method to handle that special case?
                    final CompletableFuture<Void> ensureOnlyDefinedPropertiesStage;
                    if (!allowNonModeledProperties) {
                        final FeatureProperties properties = FeatureProperties.newBuilder()
                                .set(propertyPath, propertyValue)
                                .build();
                        ensureOnlyDefinedPropertiesStage = ensureOnlyDefinedProperties(featureThingModel, dittoHeaders,
                                tdProperties, properties, containerNamePlural, true);
                    } else {
                        ensureOnlyDefinedPropertiesStage = success();
                    }

                    final CompletableFuture<Void> validatePropertiesStage;
                    if (propertyPath.getLevelCount() == 1) {
                        // potential "category update" (e.g. updating complete /configuration "ditto:category")
                        final List<Property> sameCategoryProperties = tdProperties.values().stream()
                                .filter(jsonFields -> {
                                    // gather all properties from the same category
                                    final Optional<String> dittoCategory =
                                            determineDittoCategory(featureThingModel, jsonFields);
                                    final JsonPointer categoryPath;
                                    if (propertyPath.getLevelCount() > 1) {
                                        categoryPath = propertyPath.getSubPointer(1).orElseThrow();
                                    } else {
                                        categoryPath = propertyPath;
                                    }
                                    return dittoCategory
                                            .filter(cat -> cat.equals(categoryPath.getRoot().orElseThrow().toString()))
                                            .isPresent();
                                })
                                .toList();

                        if (!sameCategoryProperties.isEmpty() && propertyValue.isObject()) {
                            log.debug("Category update of category <{}> with known properties in same category: {}",
                                    propertyPath.getRoot().orElseThrow(), sameCategoryProperties);
                            validatePropertiesStage = getValidatePropertyCategoryStage(
                                    featureThingModel, dittoHeaders, Properties.from(sameCategoryProperties),
                                    propertyPath, !desiredProperty, propertyValue.asObject(),
                                    containerNamePrefix + "property", resourcePath
                            );
                        } else {
                            validatePropertiesStage =
                                    getValidatePropertyStage(featureThingModel, dittoHeaders, tdProperties,
                                            propertyPath,
                                            !desiredProperty, propertyValue,
                                            containerNamePrefix + "property <" + propertyPath + ">",
                                            resourcePath, true
                                    );
                        }
                    } else {
                        validatePropertiesStage =
                                getValidatePropertyStage(featureThingModel, dittoHeaders, tdProperties, propertyPath,
                                        !desiredProperty, propertyValue,
                                        containerNamePrefix + "property <" + propertyPath + ">",
                                        resourcePath, true
                                );
                    }

                    return CompletableFuture.allOf(
                            ensureOnlyDefinedPropertiesStage,
                            validatePropertiesStage
                    );
                }).orElseGet(DefaultWotThingModelValidation::success);
    }

    private CompletableFuture<Void> getValidatePropertyCategoryStage(final ThingModel featureThingModel,
            final DittoHeaders dittoHeaders,
            final Properties categoryProperties,
            final JsonPointer propertyPath,
            final boolean validateRequiredObjectFields,
            final JsonObject categoryObject,
            final String propertyDescription,
            final JsonPointer resourcePath
    ) {
        final Map<String, Property> nonProvidedRequiredProperties =
                filterNonProvidedRequiredProperties(categoryProperties, featureThingModel, categoryObject, false);
        final JsonKey category = propertyPath.getRoot().orElseThrow();
        final String propertyCategoryDescription = propertyDescription + " category <" + category + ">";
        if (validateRequiredObjectFields && !nonProvidedRequiredProperties.isEmpty()) {
            final var exceptionBuilder = WotThingModelPayloadValidationException
                    .newBuilder("Required JSON fields were missing from the " + propertyCategoryDescription);
            nonProvidedRequiredProperties.forEach((rpKey, requiredProperty) ->
                    exceptionBuilder.addValidationDetail(
                            propertyPath.addLeaf(JsonKey.of(rpKey)),
                            List.of(propertyDescription + " category <" + category +
                                    ">'s <" + rpKey + "> is non optional and must be provided")
                    )
            );
            return CompletableFuture
                    .failedFuture(exceptionBuilder.dittoHeaders(dittoHeaders).build());
        }

        return getValidatePropertiesStage(
                featureThingModel,
                dittoHeaders,
                categoryProperties,
                categoryObject,
                validateRequiredObjectFields,
                propertyCategoryDescription,
                resourcePath,
                false
        );
    }

    private static <T> CompletableFuture<T> success() {
        return CompletableFuture.completedFuture(null);
    }

    private static Map<String, Property> extractRequiredProperties(final Properties tdProperties,
            final ThingModel thingModel
    ) {
        return thingModel.getTmOptional().map(tmOptionalElements -> {
            final Map<String, Property> allRequiredProperties = new LinkedHashMap<>(tdProperties);
            tmOptionalElements.stream()
                    .map(TmOptionalElement::toString)
                    .filter(el -> el.startsWith("/properties/"))
                    .map(el -> el.replace("/properties/", ""))
                    .forEach(allRequiredProperties::remove);
            return allRequiredProperties;
        }).orElseGet(LinkedHashMap::new);
    }
}
