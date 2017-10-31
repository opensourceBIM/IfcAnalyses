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
import org.bimserver.emf.IdEObject;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.interfaces.objects.SObjectType;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcObject;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcProperty;
import org.bimserver.models.ifc2x3tc1.IfcPropertySet;
import org.bimserver.models.ifc2x3tc1.IfcPropertySetDefinition;
import org.bimserver.models.ifc2x3tc1.IfcPropertySingleValue;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociatesClassification;
import org.bimserver.models.ifc2x3tc1.IfcRelDefines;
import org.bimserver.models.ifc2x3tc1.IfcRelDefinesByProperties;
import org.bimserver.models.ifc2x3tc1.IfcRoot;
import org.bimserver.models.ifc2x3tc1.impl.IfcObjectImpl;
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
import com.google.common.collect.BiMap;


public class AnalysesService extends BimBotAbstractService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private enum outputFormats {
		JSON,
		PLAIN;
	}
	
	private outputFormats outputFormat = outputFormats.JSON;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnalysesService.class);
	private final String STANDARD_SET_PREFIX = "Pset_";
		
	
	public void setOuputFormat(outputFormats outputformat)
	{
		this.outputFormat = outputformat;
	}
	
	@Override
	public BimBotsOutput runBimBot(BimBotsInput input, SObjectType settings) throws BimBotsException {
		LOGGER.debug("Starting Analayses plugin!!!! ");			

		StringBuffer extendedData = new StringBuffer();  

		IfcModelInterface model =  input.getIfcModel();
		
		ObjectNode result = OBJECT_MAPPER.createObjectNode();
		ArrayNode results = OBJECT_MAPPER.createArrayNode();
		
		BiMap<Long, IdEObject> allObjects =  model.getObjects();
		List<IfcProduct> totalObjectsList = model.getAllWithSubTypes(IfcProduct.class);
		
		
		/* 
		 * The total number of objects 
		 */
		long totalObjects =  allObjects.keySet().size();

		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalObjectsJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsJSON.put("Totalobjects", totalObjects);
			results.add(totalObjectsJSON);
		}
		else
		{
			extendedData.append("Total amount of objects: " + totalObjects + "\n");
			LOGGER.debug("Total amount of objects: " + totalObjects);			
		}
		
		
		
		/* 
		 * The total number of IfcProduct 
		 */
		long totalIfcObjects = totalObjectsList.size();
		
		if (outputFormat == outputFormats.JSON)
		{ 
			ObjectNode totalIfcObjectsJSON = OBJECT_MAPPER.createObjectNode();
			totalIfcObjectsJSON.put("IfcObjects", totalIfcObjects);
			results.add(totalIfcObjectsJSON);
		}
		else
		{
			extendedData.append("Total amount of objects: " + totalObjects + "\n");
			LOGGER.debug("Total amount of objects: " + totalObjects);			
		}
		
		long totalTriangles = 0;
		double totalM3 = 0 ;
		Map<Double, ArrayList<IfcProduct>> topMap = new HashMap<Double, ArrayList<IfcProduct>>();
		
		List<IfcProduct> allWithSubTypes = model.getAllWithSubTypes(IfcProduct.class);
		for (IfcProduct ifcProduct : allWithSubTypes) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) 
			{
				int nrTriangles = ifcProduct.getGeometry().getPrimitiveCount();
				totalTriangles += nrTriangles;
				Double volume =  IfcUtils.getIfcQuantityVolume(ifcProduct, "NetVolume");
				Double trianglesPerM3 = new Double(0);
				if (volume != null && volume > 0) 
				{
					totalM3 += volume;
					trianglesPerM3 = new Double(nrTriangles/volume.doubleValue()) ;

					if (!topMap.keySet().contains(trianglesPerM3))
					{
						topMap.put(trianglesPerM3, new ArrayList<IfcProduct>());
					} 
					topMap.get(trianglesPerM3).add(ifcProduct);
				}
			}
		}
				
		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totaltrianglesJSON = OBJECT_MAPPER.createObjectNode();
			totaltrianglesJSON.put("triangles", totalTriangles);
			results.add(totaltrianglesJSON);
			
			ObjectNode  trianglesPerM3JSON = OBJECT_MAPPER.createObjectNode();
			trianglesPerM3JSON.put("triangles_per_m3", (totalM3>0?(totalTriangles/totalM3):0));

			ArrayNode  top10JSON = OBJECT_MAPPER.createArrayNode();

			int nrOfTops = 0 ;
			Set<Double> keySet = topMap.keySet();
			Double[] keyArray = new Double[keySet.size()];
			keySet.toArray(keyArray);
			Arrays.sort(keyArray,Collections.reverseOrder());
			int counter = 0 ;
			for (Double key : keyArray)
			{
				if (nrOfTops >= 10)
					break;

				for (IfcProduct product: topMap.get(key))
				{
					ObjectNode  top10ObjectJSON = OBJECT_MAPPER.createObjectNode();		
					top10ObjectJSON.put("#",++counter );
					top10ObjectJSON.put("Oid", product.getOid() );
					top10ObjectJSON.put("Name", product.getName() );
					top10ObjectJSON.put("triangles per m3",key);
					top10JSON.add(top10ObjectJSON);
					nrOfTops++;
				}
			}
			trianglesPerM3JSON.putPOJO("Top 10", top10JSON);
			results.add(trianglesPerM3JSON);


		}
		else
		{
			extendedData.append("Total number of triangles : " + totalTriangles + "\n");
			extendedData.append("Number of triangles per m3: " + totalTriangles/totalM3 + "\n");
			LOGGER.debug("total number of triangles : " + totalTriangles);
			LOGGER.debug("number of triangles per m3: " + totalTriangles/totalM3);
			extendedData.append("Top 10 object with most number of triangles:\n");
			LOGGER.debug("Top 10 object with most number of triangles:\n");
			int nrOfTops = 0 ;
			Set<Double> keySet = topMap.keySet();
			Double[] keyArray = new Double[keySet.size()];
			keySet.toArray(keyArray);
			Arrays.sort(keyArray,Collections.reverseOrder());
			int counter = 0 ;
			for (Double key : keyArray)
			{
				if (nrOfTops >= 10)
					break;

				for (IfcProduct product: topMap.get(key))
				{
					extendedData.append("\t " + counter++ + ": " + product.getName() + "(" + product.getOid() + ") has " +  key + "trangles per m3.\n");
					LOGGER.debug("\t " + counter++ + ": " + product.getName() + "(" + product.getOid() + ") has " +  key + "trangles per m3.");
					nrOfTops++;
				}
			}
			
		}
		
		long propCount = 0;
	    long objWithPropCount = 0 ;
	    long objProxyCount = 0 ;
	    List<IfcObject> objectWithVodooSet = new ArrayList<IfcObject>();
	    List<IfcObject> objectWithVodooProp = new ArrayList<IfcObject>();
	   
 		for (Long id  : allObjects.keySet())
		{
			
 			IdEObject eObject = model.get(id);
			if (eObject.eIsProxy()) 
				objProxyCount++ ;
			
			if (eObject instanceof IfcObjectImpl && (eObject instanceof IfcPropertySet)==false && (eObject instanceof IfcPropertySingleValue)==false  )
			{
				
				IfcObject ob = (IfcObject)eObject;
				for (IfcRelDefines def : ob.getIsDefinedBy())
				{
					objWithPropCount++;
					if (def instanceof IfcRelDefinesByProperties)
					{
						IfcPropertySetDefinition propSetDef = (IfcPropertySetDefinition)((IfcRelDefinesByProperties)def).getRelatingPropertyDefinition() ;
						if (propSetDef instanceof IfcPropertySet)
						{
							
							if (!propSetDef.getName().startsWith(STANDARD_SET_PREFIX) && !objectWithVodooSet.contains(ob))				
								objectWithVodooSet.add(ob);
							for (IfcProperty prop:((IfcPropertySet)propSetDef).getHasProperties())
							{
								if (!prop.getName().startsWith(STANDARD_SET_PREFIX) && !objectWithVodooProp.contains(ob))
									objectWithVodooProp.add(ob);
								if (prop instanceof IfcPropertySingleValue){
									propCount++;
								}
							}
						}
					}
				}
			}
		}

		LOGGER.debug("Done checking objects for voodoo properties.");
		
		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalproxyJSON = OBJECT_MAPPER.createObjectNode();
			totalproxyJSON.put("Number of proxy objects", objProxyCount);
			results.add(totalproxyJSON);

			ObjectNode  totalpropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalpropertiesJSON.put("Number of properties", propCount);
			results.add(totalpropertiesJSON);

			ObjectNode  totalObjectsxWithPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithPropertiesJSON.put("Ojects with properties", objWithPropCount);
			results.add(totalObjectsxWithPropertiesJSON);

			ObjectNode  totalObjectsxWithVoodooSetsJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithVoodooSetsJSON.put("Ojects with voodoo propertieset", objectWithVodooSet.size());
			results.add(totalObjectsxWithVoodooSetsJSON);

			ObjectNode  totalObjectsxWithVoodooPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithVoodooPropertiesJSON.put("Ojects with voodoo properties", objectWithVodooProp.size());	
			results.add(totalObjectsxWithVoodooPropertiesJSON);
		}
		else
		{
			extendedData.append("Number of proxy objects: " + objProxyCount + "n");
			LOGGER.debug("Number of proxy objects: " + objProxyCount);

			extendedData.append("Number of properties: " + propCount + " in " + objWithPropCount + " objects \n");
			LOGGER.debug("Number of properties: " + propCount + " in " + objWithPropCount + " objects");
			
			extendedData.append("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooSet.size() + "n");
			LOGGER.debug("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooSet.size());	
			
			extendedData.append("IfcObject with voodoo properties (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooProp.size() + "n");
			LOGGER.debug("IfcObject with voodoo properties (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooProp.size());

		}
		
		/*
		 * Number of objects with Classification attributes
		 */
		
		ArrayList<IfcRoot> classifiedObjectsList = new ArrayList<IfcRoot>();
		
		List<IfcRelAssociatesClassification> classificationsList = model.getAllWithSubTypes(IfcRelAssociatesClassification.class);
		
		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : classificationsList)
		{
			EList<IfcRoot> a = ifcRelAssociatesClassification.getRelatedObjects();
			for (IfcRoot ifcRoot : a)
			{
				if (!classifiedObjectsList.contains(ifcRoot))
				{
					classifiedObjectsList.add(ifcRoot);
			    }
			}  
		}
		

		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalClassifications = OBJECT_MAPPER.createObjectNode();
			totalClassifications.put("Number of classification", classificationsList.size());
			results.add(totalClassifications);

			//log by Object
			ObjectNode  totalObjectsWithClassificationJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsWithClassificationJSON.put("Number of objects with classification", classifiedObjectsList.size());
			results.add(totalObjectsWithClassificationJSON);
			
		
		}
		else
		{
			// log by Objects
			
			extendedData.append("Number of objects with classification: " + classifiedObjectsList.size() + "\n");
			LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
		}	
		
		BimBotsOutput output = null;

		if (outputFormat == outputFormats.JSON)
		{
			result.putPOJO("results", results);
			String json = result.toString();
			LOGGER.debug("Adding text to extended data : " + json);	
			output = new BimBotsOutput(SchemaName.UNSTRUCTURED_UTF8_TEXT_1_0, json.getBytes(Charsets.UTF_8));

		}
		else 
		{	
			LOGGER.debug("Adding text to extended data : " + extendedData.toString());	
			output = new BimBotsOutput(SchemaName.UNSTRUCTURED_UTF8_TEXT_1_0, extendedData.toString().getBytes(Charsets.UTF_8));
		}
		
		output.setTitle("BimBotDemoService Results");
		output.setContentType("text/plain");
		return output;
	}

	@Override
	public String getOutputSchema() {
		return SchemaName.UNSTRUCTURED_UTF8_TEXT_1_0.name();
	}
}