package org.bimserver.ifc.analyses.ifc3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bimserver.bimbots.BimBotsException;
import org.bimserver.bimbots.BimBotsInput;
import org.bimserver.bimbots.BimBotsOutput;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.interfaces.objects.SObjectType;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcClassificationReference;
import org.bimserver.models.ifc2x3tc1.IfcObject;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcProperty;
import org.bimserver.models.ifc2x3tc1.IfcPropertySet;
import org.bimserver.models.ifc2x3tc1.IfcPropertySetDefinition;
import org.bimserver.models.ifc2x3tc1.IfcPropertySingleValue;
import org.bimserver.models.ifc2x3tc1.IfcProxy;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociatesClassification;
import org.bimserver.models.ifc2x3tc1.IfcRelDefines;
import org.bimserver.models.ifc2x3tc1.IfcRelDefinesByProperties;
import org.bimserver.models.ifc2x3tc1.IfcRoot;
import org.bimserver.plugins.SchemaName;
import org.bimserver.plugins.services.BimBotAbstractService;
import org.bimserver.utils.IfcUtils;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;

public class AnalysesServiceDetailed extends BimBotAbstractService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Logger LOGGER = LoggerFactory.getLogger(AnalysesServiceDetailed.class);
	private final String STANDARD_SET_PREFIX = "Pset_";

	@Override
	public BimBotsOutput runBimBot(BimBotsInput input, SObjectType settings) throws BimBotsException {
		LOGGER.debug("Starting Detailed Analayses Plugin !!!! ");

		IfcModelInterface model = input.getIfcModel();

		ObjectNode result = OBJECT_MAPPER.createObjectNode();
		ArrayNode results = OBJECT_MAPPER.createArrayNode();

		List<IfcProduct> allIfcProducts = model.getAllWithSubTypes(IfcProduct.class);

		/*
		 * The total number of objects
		 */

		ObjectNode totalObjectsJSON = OBJECT_MAPPER.createObjectNode();
		totalObjectsJSON.put("Totalobjects", model.size());
		results.add(totalObjectsJSON);
		LOGGER.debug("Total amount of objects: " + model.size());

		/*
		 * The total number of IfcProduct
		 */

		ObjectNode totalIfcObjectsJSON = OBJECT_MAPPER.createObjectNode();
		totalIfcObjectsJSON.put("IfcProducts", allIfcProducts.size());
		results.add(totalIfcObjectsJSON);
		LOGGER.debug("Total amount of objects: " + allIfcProducts.size());

		long totalTriangles = 0;
		double totalM3 = 0;
		Map<Double, List<IfcProduct>> topMap = new HashMap<>();

		for (IfcProduct ifcProduct : allIfcProducts) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				int nrTriangles = ifcProduct.getGeometry().getPrimitiveCount();
				totalTriangles += nrTriangles;
				Double volume = IfcUtils.getIfcQuantityVolume(ifcProduct, "NetVolume");
				Double trianglesPerM3 = new Double(0);
				if (volume != null && volume > 0) {
					totalM3 += volume;
					trianglesPerM3 = new Double(nrTriangles / volume.doubleValue());

					if (!topMap.keySet().contains(trianglesPerM3)) {
						topMap.put(trianglesPerM3, new ArrayList<IfcProduct>());
					}
					topMap.get(trianglesPerM3).add(ifcProduct);
				}
			}
		}

		ObjectNode totaltrianglesJSON = OBJECT_MAPPER.createObjectNode();
		totaltrianglesJSON.put("triangles", totalTriangles);
		results.add(totaltrianglesJSON);

		ObjectNode trianglesPerM3JSON = OBJECT_MAPPER.createObjectNode();
		trianglesPerM3JSON.put("triangles_per_m3", (totalM3 > 0 ? (totalTriangles / totalM3) : 0));
		LOGGER.debug("total number of triangles : " + totalTriangles);
		LOGGER.debug("number of triangles per m3: " + totalTriangles / totalM3);
		LOGGER.debug("Top 10 object with most number of triangles:\n");

		ArrayNode top10JSON = OBJECT_MAPPER.createArrayNode();

		int nrOfTops = 0;
		Set<Double> keySet = topMap.keySet();
		Double[] keyArray = new Double[keySet.size()];
		keySet.toArray(keyArray);
		Arrays.sort(keyArray, Collections.reverseOrder());
		int counter = 0;
		for (Double key : keyArray) {
			if (nrOfTops >= 10)
				break;

			for (IfcProduct product : topMap.get(key)) {
				LOGGER.debug("\t " + counter + ": " + product.getName() + "(" + product.getOid() + ") has " + key
						+ "trangles per m3.");
				ObjectNode top10ObjectJSON = OBJECT_MAPPER.createObjectNode();
				top10ObjectJSON.put("#", ++counter);
				top10ObjectJSON.put("Oid", product.getOid());
				top10ObjectJSON.put("Name", product.getName());
				top10ObjectJSON.put("triangles per m3", key);
				top10JSON.add(top10ObjectJSON);
				nrOfTops++;
			}
		}
		trianglesPerM3JSON.putPOJO("Top 10", top10JSON);

		results.add(trianglesPerM3JSON);

		long propCount = 0;
		long objWithPropCount = 0;
		long objProxyCount = 0;
		List<IfcObject> objectWithVodooSet = new ArrayList<IfcObject>();
		List<IfcObject> objectWithVodooProp = new ArrayList<IfcObject>();

		for (IfcProduct product : allIfcProducts) {

			if (product instanceof IfcProxy)
				objProxyCount++;

			for (IfcRelDefines def : product.getIsDefinedBy()) {
				objWithPropCount++;
				if (def instanceof IfcRelDefinesByProperties) {
					IfcPropertySetDefinition propSetDef = (IfcPropertySetDefinition) ((IfcRelDefinesByProperties) def)
							.getRelatingPropertyDefinition();
					if (propSetDef instanceof IfcPropertySet) {

						if (!propSetDef.getName().startsWith(STANDARD_SET_PREFIX)
								&& !objectWithVodooSet.contains(product))
							objectWithVodooSet.add(product);
						for (IfcProperty prop : ((IfcPropertySet) propSetDef).getHasProperties()) {
							if (!prop.getName().startsWith(STANDARD_SET_PREFIX)
									&& !objectWithVodooProp.contains(product))
								objectWithVodooProp.add(product);
							if (prop instanceof IfcPropertySingleValue) {
								propCount++;
							}
						}
					}
				}
			}
		}

		LOGGER.debug("Done checking objects for voodoo properties.");

		ObjectNode totalproxyJSON = OBJECT_MAPPER.createObjectNode();
		totalproxyJSON.put("Number of proxy objects", objProxyCount);
		results.add(totalproxyJSON);

		ObjectNode totalpropertiesJSON = OBJECT_MAPPER.createObjectNode();
		totalpropertiesJSON.put("Number of properties", propCount);
		results.add(totalpropertiesJSON);

		ObjectNode totalObjectsxWithPropertiesJSON = OBJECT_MAPPER.createObjectNode();
		totalObjectsxWithPropertiesJSON.put("Ojects with properties", objWithPropCount);
		results.add(totalObjectsxWithPropertiesJSON);

		ObjectNode totalObjectsxWithVoodooPropertiesSetsJSON = OBJECT_MAPPER.createObjectNode();
		totalObjectsxWithVoodooPropertiesSetsJSON.put("Ojects with voodoo properties sets", objectWithVodooSet.size());

		ObjectNode totalObjectsxWithVoodooPropertiesJSON = OBJECT_MAPPER.createObjectNode();
		totalObjectsxWithVoodooPropertiesJSON.put("Ojects with voodoo properties", objectWithVodooProp.size());

		LOGGER.debug("Number of proxy objects: " + objProxyCount);
		LOGGER.debug("Number of properties: " + propCount + " in " + objWithPropCount + " objects");
		LOGGER.debug("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): "
				+ objectWithVodooSet.size());
		LOGGER.debug("IfcObject with voodoo sets: " + objectWithVodooProp.size());

		ArrayNode objectWithVodooPropertiesSetArrayJSON = OBJECT_MAPPER.createArrayNode();

		for (IfcObject obj : objectWithVodooSet) {
			LOGGER.debug("\t" + obj.getName());

			ObjectNode setNodeJSON = OBJECT_MAPPER.createObjectNode();
			setNodeJSON.put("Object", obj.getName());
			setNodeJSON.put("ObjectId", obj.getOid());
			objectWithVodooPropertiesSetArrayJSON.add(setNodeJSON);

		}
		totalObjectsxWithVoodooPropertiesSetsJSON.putPOJO("objectsWithPropertieSet",
				objectWithVodooPropertiesSetArrayJSON);
		results.add(totalObjectsxWithVoodooPropertiesSetsJSON);

		ArrayNode objectWithVodooPropertiesArrayJSON = OBJECT_MAPPER.createArrayNode();
		LOGGER.debug("IfcObject with voodoo properties: " + objectWithVodooProp.size());

		for (IfcObject obj : objectWithVodooProp) {
			LOGGER.debug("\t" + obj.getName());

			ObjectNode propNodeJSON = OBJECT_MAPPER.createObjectNode();
			propNodeJSON.put("Object", obj.getName());
			propNodeJSON.put("ObjectId", obj.getOid());
			objectWithVodooPropertiesArrayJSON.add(propNodeJSON);
		}
		totalObjectsxWithVoodooPropertiesJSON.putPOJO("objectsWithProperties", objectWithVodooPropertiesArrayJSON);

		results.add(totalObjectsxWithVoodooPropertiesJSON);

		/*
		 * Classifications by Kind
		 */

		ArrayList<IfcRoot> classifiedObjectsList = new ArrayList<IfcRoot>();
		List<IfcRelAssociatesClassification> classificationsList = model
				.getAllWithSubTypes(IfcRelAssociatesClassification.class);
		Map<String, List<IfcRoot>> classificationByKinds = new HashMap<String, List<IfcRoot>>();

		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : classificationsList) {
			if (ifcRelAssociatesClassification.getRelatingClassification() instanceof IfcClassificationReference) {
				if (!classificationByKinds.containsKey(
						((IfcClassificationReference) ifcRelAssociatesClassification.getRelatingClassification())
								.getName()))
					classificationByKinds.put(
							((IfcClassificationReference) ifcRelAssociatesClassification.getRelatingClassification())
									.getName(),
							new ArrayList<>());
				EList<IfcRoot> a = ifcRelAssociatesClassification.getRelatedObjects();
				for (IfcRoot ifcRoot : a) {
					classificationByKinds.get(
							((IfcClassificationReference) ifcRelAssociatesClassification.getRelatingClassification())
									.getName())
							.add(ifcRoot);
					if (!classifiedObjectsList.contains(ifcRoot)) {
						classifiedObjectsList.add(ifcRoot);
					}
				}
			}
		}

		ObjectNode totalClassifications = OBJECT_MAPPER.createObjectNode();
		totalClassifications.put("Number of classification", classificationsList.size());

		// log by kinds
		ArrayNode classificationTypeArrayJSON = OBJECT_MAPPER.createArrayNode();

		LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
		LOGGER.debug("Type of classifications:");

		for (String classification : classificationByKinds.keySet()) {
			LOGGER.debug("\t" + classification + ":");

			ArrayNode objectsWithClassificationTypeArrayJSON = OBJECT_MAPPER.createArrayNode();
			ObjectNode classificationTypeJSON = OBJECT_MAPPER.createObjectNode();

			classificationTypeJSON.put("#Objects", classificationByKinds.get(classification).size());
			classificationTypeJSON.put("Classification", classification);
			for (IfcRoot object : classificationByKinds.get(classification)) {
				LOGGER.debug("\t\t" + object.getName());
				ObjectNode objectWithClassificationTypeJSON = OBJECT_MAPPER.createObjectNode();
				objectWithClassificationTypeJSON.put("Object", object.getName());
				objectWithClassificationTypeJSON.put("ObjectId", object.getOid());
				objectsWithClassificationTypeArrayJSON.add(objectWithClassificationTypeJSON);
			}
			classificationTypeJSON.putPOJO("objects", objectsWithClassificationTypeArrayJSON);

			classificationTypeArrayJSON.add(classificationTypeJSON);
		}
		totalClassifications.putPOJO("Classifications", classificationTypeArrayJSON);

		results.add(totalClassifications);

		BimBotsOutput output = null;

		result.putPOJO("results", results);
		LOGGER.debug("Adding text to extended data : " + result.toString());
		output = new BimBotsOutput(SchemaName.UNSTRUCTURED_UTF8_TEXT_1_0, result.toString().getBytes(Charsets.UTF_8));

		output.setTitle("Analyses Detailed Results");
		output.setContentType("application/json");
		return output;
	}

	@Override
	public SchemaName getOutputSchema() {
		return SchemaName.UNSTRUCTURED_UTF8_TEXT_1_0;
	}

}
