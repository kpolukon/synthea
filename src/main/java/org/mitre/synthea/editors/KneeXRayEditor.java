package org.mitre.synthea.editors;

import org.mitre.synthea.engine.HealthRecordEditor;
import org.mitre.synthea.export.DICOMExporter;
import org.mitre.synthea.helpers.DICOMFileSelector;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class KneeXRayEditor implements HealthRecordEditor {

  public static String OA_OF_KNEE = "239873007";
  public static String KNEE_XRAY = "74016001";

  @Override
  public boolean shouldRun(Person person, HealthRecord record, long time) {
    try {
      return person.record.conditionActive(OA_OF_KNEE) && DICOMFileSelector.filesRemain();
    } catch (IOException e) {
      System.out.println("Unable to see if there are DICOM files to use");
      e.printStackTrace();
      return false;
    }
  }

  @Override
  public void process(Person person, List<HealthRecord.Encounter> encounters, long time) {
    List<HealthRecord.Encounter> xrayEncounters = encountersWithImagingStudiesOfCode(encounters, KNEE_XRAY);
    List<HealthRecord.Encounter> xrayAndOAEncounters = encountersWithConditionsOfCode(xrayEncounters, OA_OF_KNEE);
    xrayAndOAEncounters.forEach(encounter -> {
      HealthRecord.ImagingStudy is = encounter.imagingStudies.get(0);
      try {
        String sourceFile = DICOMFileSelector.selectRandomDICOMFile(person);
        String targetFile = DICOMExporter.outputDICOMFile(person, is.dicomUid);
        DICOMExporter.writeDICOMAttributes(is.dicomUid, is.start, person, sourceFile, targetFile);
        //is.fileLocation = targetFile;
      } catch (IOException e) {
        System.out.println("Unable to write DICOM file for knee XRay");
        e.printStackTrace();
      }
    });
  }

  /**
   * Filter a list of encounters to find all that have an ImagingStudy with a particular code.
   * @param encounters The list to filter
   * @param code The code to look for
   * @return The filtered list. If there are no matching encounters, then an empty list.
   */
  public List<HealthRecord.Encounter> encountersWithImagingStudiesOfCode(
          List<HealthRecord.Encounter> encounters,
          String code) {
    return encounters.stream().filter(e -> {
      return e.imagingStudies.stream().anyMatch(imagingStudy -> {
        return imagingStudy.codes.stream().anyMatch(c -> code.equals(c.code));
      });
    }).collect(Collectors.toList());
  }

  /**
   * Filter a list of encounters to find all that have an condition with a particular code.
   * @param encounters The list to filter
   * @param code The code to look for
   * @return The filtered list. If there are no matching encounters, then an empty list.
   */
  public List<HealthRecord.Encounter> encountersWithConditionsOfCode(
          List<HealthRecord.Encounter> encounters,
          String code) {
    return encounters.stream().filter(e -> {
      return e.conditions.stream().anyMatch(condition -> {
        return condition.codes.stream().anyMatch(c -> code.equals(c.code));
      });
    }).collect(Collectors.toList());
  }
}