package org.mitre.synthea.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Components.Attachment;
import org.mitre.synthea.engine.Components.SampledData;
import org.mitre.synthea.export.ExportHelper;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.TimeSeriesData;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.modules.CardiovascularDiseaseModule;
import org.mitre.synthea.modules.DeathModule;
import org.mitre.synthea.modules.EncounterModule;
import org.mitre.synthea.modules.LifecycleModule;
import org.mitre.synthea.modules.QualityOfLifeModule;
import org.mitre.synthea.modules.WeightLossModule;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.ClinicianSpecialty;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Location;
import org.mockito.Mockito;
import org.powermock.reflect.Whitebox;


public class StateTest {

  private Person person;
  private long time;
  private boolean physStateEnabled;

  /**
   * Setup State tests.
   * @throws IOException On File IO errors.
   */
  @Before
  public void setup() throws IOException {
    time = System.currentTimeMillis();

    person = new Person(0L);
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put(Person.FIRST_LANGUAGE, "spanish");
    person.attributes.put(Person.RACE, "other");
    person.attributes.put(Person.ETHNICITY, "hispanic");
    person.attributes.put(Person.INCOME, Integer.parseInt(Config
        .get("generate.demographics.socioeconomic.income.poverty")) * 2);
    person.attributes.put(Person.OCCUPATION_LEVEL, 1.0);

    person.history = new LinkedList<>();
    Provider mock = Mockito.mock(Provider.class, withSettings().serializable());
    Mockito.when(mock.getResourceID()).thenReturn("Mock-UUID");
    person.setProvider(EncounterType.AMBULATORY, mock);
    person.setProvider(EncounterType.WELLNESS, mock);
    person.setProvider(EncounterType.EMERGENCY, mock);
    person.setProvider(EncounterType.INPATIENT, mock);

    int age = 35;
    time = System.currentTimeMillis();
    long birthTime = time - Utilities.convertTime("years", age);
    person.attributes.put(Person.BIRTHDATE, birthTime);

    String testStateDefault = Config.get("test_state.default", "Massachusetts");
    PayerManager.loadPayers(new Location(testStateDefault, null));
    person.coverage.setPlanToNoInsurance((long) person.attributes.get(Person.BIRTHDATE));
    person.coverage.setPlanToNoInsurance(time);
    // Ensure Physiology state is enabled by default
    physStateEnabled = State.ENABLE_PHYSIOLOGY_STATE;
    State.ENABLE_PHYSIOLOGY_STATE = true;
  }

  /**
   * Reset state after State tests.
   */
  @After
  public void tearDown() {
    State.ENABLE_PHYSIOLOGY_STATE = physStateEnabled;
  }

  private void simulateWellnessEncounter(Module module, Person person, long time) {
    // "true" indicates that we are in a wellness encounter (true)
    person.attributes.put(EncounterModule.ACTIVE_WELLNESS_ENCOUNTER, true);
    // "false" indicates that the module has not used its "pass" yet
    person.attributes.put(EncounterModule.ACTIVE_WELLNESS_ENCOUNTER + " " + module.name, false);
    EncounterModule.createEncounter(person, time, EncounterType.WELLNESS,
        ClinicianSpecialty.GENERAL_PRACTICE,
        EncounterModule.ENCOUNTER_CHECKUP, EncounterModule.NAME);
  }

  @Test
  public void initial_always_passes() throws Exception {
    Module module = TestHelper.getFixture("initial_to_terminal.json");
    State initial = module.getState("Initial");
    assertTrue(initial.process(person, time));
  }

  @Test
  public void terminal_never_passes() throws Exception {
    Module module = TestHelper.getFixture("initial_to_terminal.json");
    State terminal = module.getState("Terminal");
    assertFalse(terminal.process(person, time));
    assertFalse(terminal.process(person, time + TimeUnit.DAYS.toMillis(7)));
  }

  @Test(expected = RuntimeException.class)
  public void stateMustHaveTransition() throws Exception {
    TestHelper.getFixture("state_without_transition.json");
  }

  @Test
  public void guard_passes_when_condition_is_met() throws Exception {
    Module module = TestHelper.getFixture("guard.json");
    State guard = module.getState("Gender_Guard");
    person.attributes.put(Person.GENDER, "F");
    assertTrue(guard.process(person, time));
  }

  @Test
  public void guard_blocks_when_condition_isnt_met() throws Exception {
    Module module = TestHelper.getFixture("guard.json");
    State guard = module.getState("Gender_Guard");
    person.attributes.put(Person.GENDER, "M");
    assertFalse(guard.process(person, time));
  }

  @Test
  public void counter() throws Exception {
    Module module = TestHelper.getFixture("counter.json");

    assertTrue(person.attributes.get("loop_index") == null);

    State counter = module.getState("Counter");
    assertTrue(counter.process(person, time));
    assertEquals(1.0, person.attributes.get("loop_index"));

    assertTrue(counter.process(person, time));
    assertEquals(2.0, person.attributes.get("loop_index"));

    assertTrue(counter.process(person, time));
    assertEquals(3.0, person.attributes.get("loop_index"));

    State decrement = module.getState("Counter_Decrement_by_2");
    assertTrue(decrement.process(person, time));
    assertEquals(1.0, person.attributes.get("loop_index"));

    assertTrue(decrement.process(person, time));
    assertEquals(-1.0, person.attributes.get("loop_index"));
  }

  @Test
  public void condition_onset() throws Exception {
    // Setup a mock to track calls to the patient record
    // In this case, the record shouldn't be called at all
    person.record = Mockito.mock(HealthRecord.class);

    Module module = TestHelper.getFixture("condition_onset.json");
    State condition = module.getState("Diabetes");
    // Should pass through this state immediately without calling the record
    assertTrue(condition.process(person, time));

    verifyNoInteractions(person.record);
  }

  @Test
  public void condition_onset_last_onset_time() throws Exception {
    Module module = TestHelper.getFixture("condition_onset.json");
    State condition = module.getState("Diabetes");
    // Should pass through this state immediately without calling the record
    assertTrue(condition.process(person, time));
    String conditionDisplay = "Diabetes mellitus";
    Long onsetTime = person.getOnsetConditionRecord().getConditionLastOnsetTimeFromModule(
        module.name, conditionDisplay
    );
    assertTrue(onsetTime != null);
    // Ensure that it ignores the blank assign_to_attribute
    assertNull(person.attributes.get(""));
    assertEquals(time, onsetTime.longValue());
  }

  @Test
  public void condition_onset_diagnosed_by_target_encounter() throws Exception {
    Module module = TestHelper.getFixture("condition_onset.json");

    State condition = module.getState("Diabetes");
    // Should pass through this state immediately without calling the record
    person.history.add(0, condition);
    assertTrue(condition.process(person, time));

    // The encounter comes next (and add it to history);
    State encounter = module.getState("ED_Visit");
    person.history.add(0, encounter); // states are added to history before being processed
    assertTrue(encounter.process(person, time));

    assertEquals(1, person.record.encounters.size());
    Encounter enc = person.record.encounters.get(0);
    Code code = enc.codes.get(0);
    assertEquals("50849002", code.code);
    assertEquals("Emergency room admission (procedure)", code.display);
    assertEquals(1, enc.conditions.size());
    code = enc.conditions.get(0).codes.get(0);
    assertEquals("73211009", code.code);
    assertEquals("Diabetes mellitus", code.display);
    Long onsetTime = person.getOnsetConditionRecord().getConditionLastOnsetTimeFromModule(
        module.name, code.display
    );
    assertTrue(onsetTime != null);
    assertEquals(time, onsetTime.longValue());
  }

  @Test
  public void condition_onset_during_encounter() throws Exception {
    Module module = TestHelper.getFixture("condition_onset.json");
    // The encounter comes first (and add it to history);
    State encounter = module.getState("ED_Visit");

    assertTrue(encounter.process(person, time));
    person.history.add(0, encounter);

    // Then appendicitis is diagnosed
    State appendicitis = module.getState("Appendicitis");
    assertTrue(appendicitis.process(person, time));

    assertEquals(1, person.record.encounters.size());
    Encounter enc = person.record.encounters.get(0);
    Code code = enc.codes.get(0);
    assertEquals("50849002", code.code);
    assertEquals("Emergency room admission (procedure)", code.display);
    assertEquals(1, enc.conditions.size());
    code = enc.conditions.get(0).codes.get(0);
    assertEquals("47693006", code.code);
    assertEquals("Rupture of appendix", code.display);
    Long onsetTime = person.getOnsetConditionRecord().getConditionLastOnsetTimeFromModule(
        module.name, code.display
    );
    assertTrue(onsetTime != null);
    assertNotNull(person.attributes.get("Most Recent ED Visit"));
    assertEquals(time, onsetTime.longValue());
  }

  /**
   * Previously, if there were multiple calls to ConditionOnset, the condition Entry would contain
   * multiple Codes, one for each time the ConditionOnset was invoked. This test checks to make
   * sure that the same code is only added once.
   * @throws Exception when bad things happen
   */
  @Test
  public void condition_onset_during_encounter_prevent_multiple_coding() throws Exception {
    Module module = TestHelper.getFixture("condition_onset.json");
    // The encounter comes first (and add it to history);
    State encounter = module.getState("ED_Visit");

    assertTrue(encounter.process(person, time));
    person.history.add(0, encounter);

    // Then appendicitis is diagnosed
    State appendicitis = module.getState("Appendicitis");
    assertTrue(appendicitis.process(person, time));
    // Call the same ConditionOnset
    assertTrue(appendicitis.process(person, time));

    Encounter enc = person.record.encounters.get(0);
    assertEquals(1, enc.conditions.get(0).codes.size());
    Code code = enc.conditions.get(0).codes.get(0);
    assertEquals("47693006", code.code);
    assertEquals("Rupture of appendix", code.display);
    Long onsetTime = person.getOnsetConditionRecord().getConditionLastOnsetTimeFromModule(
        module.name, code.display
    );
    assertTrue(onsetTime != null);
    assertEquals(time, onsetTime.longValue());
  }

  @Test
  public void allergy_onset() throws Exception {
    // Setup a mock to track calls to the patient record
    // In this case, the record shouldn't be called at all
    person.record = Mockito.mock(HealthRecord.class);

    Module module = TestHelper.getFixture("allergies.json");
    State allergy = module.getState("Allergy_to_Eggs");
    // Should pass through this state immediately without calling the record
    assertTrue(allergy.process(person, time));

    verifyNoInteractions(person.record);
  }

  @Test
  public void gmfTwoDelayExactTime() throws Exception {
    Module module = TestHelper.getFixture("gmf_two_point_oh.json");

    // Seconds
    State delay = module.getState("2_Second_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000));
    assertTrue(delay.process(person, time + 2L * 1000));
    assertTrue(delay.process(person, time + 3L * 1000));
  }

  @Test
  public void delay_passes_after_exact_time() throws Exception {
    Module module = TestHelper.getFixture("delay.json");

    // Seconds
    State delay = module.getState("2_Second_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000));
    assertTrue(delay.process(person, time + 2L * 1000));
    assertTrue(delay.process(person, time + 3L * 1000));

    // Minutes
    delay = module.getState("2_Minute_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60));
    assertTrue(delay.process(person, time + 2L * 1000 * 60));
    assertTrue(delay.process(person, time + 3L * 1000 * 60));

    // Hours
    delay = module.getState("2_Hour_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60));
    assertTrue(delay.process(person, time + 2L * 1000 * 60 * 60));
    assertTrue(delay.process(person, time + 3L * 1000 * 60 * 60));

    // Days
    delay = module.getState("2_Day_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24));
    assertTrue(delay.process(person, time + 2L * 1000 * 60 * 60 * 24));
    assertTrue(delay.process(person, time + 3L * 1000 * 60 * 60 * 24));

    // Weeks
    delay = module.getState("2_Week_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24 * 7));
    assertTrue(delay.process(person, time + 2L * 1000 * 60 * 60 * 24 * 7));
    assertTrue(delay.process(person, time + 3L * 1000 * 60 * 60 * 24 * 7));

    // Months
    // NOTE: months + years are not "well-defined" like the smaller units of time
    // so these may be flaky around things like leap years & DST changes
    delay = module.getState("2_Month_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24 * 30));
    assertTrue(delay.process(person, time + 2L * 1000 * 60 * 60 * 24 * 30));
    assertTrue(delay.process(person, time + 3L * 1000 * 60 * 60 * 24 * 30));

    // Years
    delay = module.getState("2_Year_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24 * 365));
    assertTrue(delay.process(person, time + 2L * 1000 * 60 * 60 * 24 * 365));
    assertTrue(delay.process(person, time + 3L * 1000 * 60 * 60 * 24 * 365));
  }

  @Test
  public void gausian_delay_never_negative() throws Exception {
    Module module = TestHelper.getFixture("gaussian_distro_delay.json");

    // Seconds
    State delay = module.getState("1 Mean Delay");
    for (int i = 0; i < 100; i++) {
      State.Delay daClone = (State.Delay) delay.clone();
      daClone.entered = time;
      daClone.process(person, time);
      assertTrue(daClone.next >= time);
    }
  }

  @Test
  public void gausian_delay_has_correct_mean() throws Exception {
    Module module = TestHelper.getFixture("gaussian_distro_delay.json");

    long acc = 0;
    // Seconds
    State delay = module.getState("10 Mean Delay");
    for (int i = 0; i < 1000; i++) {
      State.Delay daClone = (State.Delay) delay.clone();
      daClone.entered = time;
      daClone.process(person, time);
      acc += (daClone.next - time);
    }
    long mean = acc / 1000;
    assertTrue(mean > 9500);
    assertTrue(mean < 10500);
  }

  @Test
  public void delay_passes_after_time_range() throws Exception {
    Module module = TestHelper.getFixture("delay.json");

    // Seconds
    State delay = module.getState("2_To_10_Second_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000));
    assertFalse(delay.process(person, time - 1 + 2L * 1000));
    assertTrue(delay.process(person, time + 1 + 10L * 1000));

    // Minutes
    delay = module.getState("2_To_10_Minute_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60));
    assertFalse(delay.process(person, time - 1 + 2L * 1000 * 60));
    assertTrue(delay.process(person, time + 1 + 10L * 1000 * 60));

    // Hours
    delay = module.getState("2_To_10_Hour_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60));
    assertFalse(delay.process(person, time - 1 + 2L * 1000 * 60 * 60));
    assertTrue(delay.process(person, time + 1 + 10L * 1000 * 60 * 60));

    // Days
    delay = module.getState("2_To_10_Day_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24));
    assertFalse(delay.process(person, time - 1 + 2L * 1000 * 60 * 60 * 24));
    assertTrue(delay.process(person, time + 1 + 10L * 1000 * 60 * 60 * 24));

    // Weeks
    delay = module.getState("2_To_10_Week_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24 * 7));
    assertFalse(delay.process(person, time - 1 + 2L * 1000 * 60 * 60 * 24 * 7));
    assertTrue(delay.process(person, time + 1 + 10L * 1000 * 60 * 60 * 24 * 7));

    // Months
    delay = module.getState("2_To_10_Month_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24 * 30));
    assertFalse(delay.process(person, time - 1 + 2L * 1000 * 60 * 60 * 24 * 30));
    assertTrue(delay.process(person, time + 1 + 10L * 1000 * 60 * 60 * 24 * 30));

    // Years
    delay = module.getState("2_To_10_Year_Delay");
    delay.entered = time;
    assertFalse(delay.process(person, time));
    assertFalse(delay.process(person, time + 1L * 1000 * 60 * 60 * 24 * 365));
    assertFalse(delay.process(person, time - 1 + 2L * 1000 * 60 * 60 * 24 * 365));
    assertTrue(delay.process(person, time + 1 + 10L * 1000 * 60 * 60 * 24 * 365));
  }

  @Test
  public void death_during_delay() throws Exception {
    Module module = TestHelper.getFixture("death_during_delay.json");

    // patient is alive
    assertTrue(person.alive(time));

    // patient dies during delay
    module.process(person, time);

    // patient is still alive now...
    assertTrue(person.alive(time));

    // patient is dead later...
    long step = Utilities.convertTime("days", 7);
    assertFalse(person.alive(time + step));

    // patient has one encounter...
    assertTrue(person.hadPriorState("Encounter 1"));
    assertEquals(1, person.record.encounters.size());
    assertFalse(person.hadPriorState("Encounter Should Not Happen"));

    // next time step...
    module.process(person, time + step);

    // patient is still dead...
    assertFalse(person.alive(time + step));

    // patient still has one encounter...
    assertTrue(person.hadPriorState("Encounter 1"));
    assertEquals(1, person.record.encounters.size());
    assertFalse(person.hadPriorState("Encounter Should Not Happen"));
  }

  @Test
  public void deathAfterProcedureWithDuration() throws Exception {
    Module module = TestHelper.getFixture("procedure_duration_and_death.json");

    // patient is alive
    assertTrue(person.alive(time));

    // patient dies during the process
    module.process(person, time);
    long nextStep = time + Utilities.convertTime("days", 7);
    module.process(person, nextStep);

    // patient is dead later...
    assertFalse(person.alive(nextStep));

    // patient has one encounter...
    assertTrue(person.hadPriorState("Encounter 1"));
    assertEquals(1, person.record.encounters.size());

    long deathDate = (long) person.attributes.get(Person.DEATHDATE);
    long encounterStop = person.record.encounters.get(0).stop;
    assertTrue(deathDate >= encounterStop);
  }

  @Test
  public void gmfTwoVitalSign() throws Exception {
    // Setup a mock to track calls to the patient record
    // In this case, the record shouldn't be called at all
    person.record = Mockito.mock(HealthRecord.class);

    Module module = TestHelper.getFixture("gmf_two_point_oh.json");

    State vitalSign = module.getState("VitalSign").clone();
    assertTrue(vitalSign.process(person, time));

    assertTrue(person.getVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, time) >= 110);
    assertTrue(person.getVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, time) <= 130);

    verifyNoInteractions(person.record);
  }


  @Test
  public void vitalsign() throws Exception {
    // Setup a mock to track calls to the patient record
    // In this case, the record shouldn't be called at all
    person.record = Mockito.mock(HealthRecord.class);

    Module module = TestHelper.getFixture("observation.json");

    State vitalsign = module.getState("VitalSign").clone();
    assertTrue(vitalsign.process(person, time));

    assertEquals(120.0, person.getVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, time), 0.0);

    verifyNoInteractions(person.record);
  }

  @Test
  public void symptoms_last_updated_time() throws Exception {
    Module module = TestHelper.getFixture("symptom.json");

    State symptom1 = module.getState("SymptomOnset");
    assertTrue(symptom1.process(person, time));
    Long updatedTime = person.getSymptomLastUpdatedTime(module.name, "Chest Pain");
    assertTrue(updatedTime != null);
    assertEquals(time, updatedTime.longValue());
  }

  @Test
  public void gmfTwoSymptom() throws Exception {
    Module module = TestHelper.getFixture("gmf_two_point_oh.json");

    State symptom1 = module.getState("SymptomOnset");
    assertTrue(symptom1.process(person, time));
    int symptomValue = person.getSymptom("Chest Pain");
    assertTrue(1 <= symptomValue && symptomValue <= 10);
  }

  @Test
  public void symptoms() throws Exception {
    Module module = TestHelper.getFixture("symptom.json");

    State symptom1 = module.getState("SymptomOnset");
    assertTrue(symptom1.process(person, time));
    int symptomValue = person.getSymptom("Chest Pain");
    assertTrue(1 <= symptomValue && symptomValue <= 10);

    State symptom2 = module.getState("SymptomWorsen");
    assertTrue(symptom2.process(person, time));
    assertEquals(96, person.getSymptom("Chest Pain"));
  }

  @Test
  public void symptoms50() throws Exception {
    Module module = TestHelper.getFixture("symptom50.json");

    State symptom50 = module.getState("Symptom50");
    assertTrue(symptom50.process(person, time));
  }

  @Test
  public void setAttribute_with_value() throws Exception {
    Module module = TestHelper.getFixture("set_attribute.json");

    person.attributes.remove("Current Opioid Prescription");
    State set1 = module.getState("Set_Attribute_1");
    assertTrue(set1.process(person, time));

    assertEquals("Vicodin", person.attributes.get("Current Opioid Prescription"));
  }

  @Test
  public void gmfTwoSetAttributeWithValue() throws Exception {
    Module module = TestHelper.getFixture("gmf_two_point_oh.json");

    person.attributes.remove("Favorite Number");
    State set1 = module.getState("Set_Attribute_1");
    assertTrue(set1.process(person, time));

    assertEquals(2.0, person.attributes.get("Favorite Number"));
  }

  @Test
  public void setAttribute_without_value() throws Exception {
    Module module = TestHelper.getFixture("set_attribute.json");

    person.attributes.put("Current Opioid Prescription", "Vicodin");
    State set2 = module.getState("Set_Attribute_2");
    assertTrue(set2.process(person, time));

    assertNull(person.attributes.get("Current Opioid Prescription"));
  }

  @Test
  public void setAttribute_with_expression() throws Exception {
    Module module = TestHelper.getFixture("set_attribute.json");

    State set3 = module.getState("Set_Attribute_3");
    assertTrue(set3.process(person, time));

    assertEquals(185, ((BigDecimal) person.attributes.get("Maximum Heart Rate"))
        .doubleValue(), 0.1);
  }

  @Test
  public void setAttribute_with_seriesData() throws Exception {
    Module module = TestHelper.getFixture("set_attribute.json");

    State set4 = module.getState("Set_Attribute_4");
    assertTrue(set4.process(person, time));

    TimeSeriesData data = (TimeSeriesData) person.attributes.get("ECG");

    assertEquals(10, data.getValues().size());
    assertEquals(2041, data.getValues().get(0), 0.0001);
  }

  @Test
  public void setAttribute_with_seriesData_Module() throws Exception {
    Module module = TestHelper.getFixture("series_data.json");
    assertTrue(module.process(person, time));

    TimeSeriesData data = (TimeSeriesData) person.attributes.get("series_data");
    assertEquals(12, data.getValues().size());
    assertEquals(1, data.getValues().get(0), 0.0001);

    HealthRecord.Observation obs = person.record.getLatestObservation("1234");
    assertNotNull(obs);
    assertTrue(obs.value instanceof Components.SampledData);

    Components.SampledData sampledData = (Components.SampledData) obs.value;
    String value = ExportHelper.sampledDataToValueString(sampledData);
    assertEquals("1 2 3 4 4 3 2 1 1 2 3 4", value);
  }

  @Test
  public void setAttribute_with_bad_seriesData() throws Exception {
    Module module = TestHelper.getFixture("set_attribute.json");

    State set5 = module.getState("Set_Attribute_5");

    try {
      set5.process(person, time);
      fail("Expected RuntimeException to be thrown");
    } catch (RuntimeException ex) {
      assertEquals("unable to parse \"invalid\" in SetAttribute state for \"ECG\"",
          ex.getMessage());
    }
  }

  @Test
  public void gmfTwoProcedure() throws Exception {
    person.attributes.remove("Most Recent Surgery");
    Module module = TestHelper.getFixture("gmf_two_point_oh.json");

    State appendectomy = module.getState("Appendectomy");
    appendectomy.process(person, time);

    HealthRecord.Procedure procedure = (HealthRecord.Procedure) person.attributes
        .get("Most Recent Surgery");

    assertEquals(time, procedure.start);

    Code code = procedure.codes.get(0);

    assertEquals("6025007", code.code);
    assertEquals("Laparoscopic appendectomy", code.display);
  }

  @Test
  public void procedure_assigns_entity_attribute() throws Exception {
    person.attributes.remove("Most Recent Surgery");
    Module module = TestHelper.getFixture("procedure.json");
    State encounter = module.getState("Inpatient_Encounter");
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    State appendectomy = module.getState("Appendectomy");
    appendectomy.process(person, time);

    HealthRecord.Procedure procedure = (HealthRecord.Procedure) person.attributes
        .get("Most Recent Surgery");

    assertEquals(time, procedure.start);

    Code code = procedure.codes.get(0);

    assertEquals("6025007", code.code);
    assertEquals("Laparoscopic appendectomy", code.display);
  }

  @Test
  public void procedure_during_encounter() throws Exception {
    Module module = TestHelper.getFixture("procedure.json");

    // The encounter comes first (and add it to history);
    State encounter = module.getState("Inpatient_Encounter");

    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Then have the appendectomy
    State appendectomy = module.getState("Appendectomy");
    appendectomy.entered = time;
    // Procedure should block
    assertTrue(!appendectomy.process(person, time));
    long nextStep = time + Utilities.convertTime("days", 7);
    assertTrue(appendectomy.process(person, nextStep));

    List<HealthRecord.Procedure> procedures = person.record.encounters.get(0).procedures;
    assertEquals(1, procedures.size());

    HealthRecord.Procedure proc = procedures.get(0);
    Code code = proc.codes.get(0);

    assertEquals("6025007", code.code);
    assertEquals("Laparoscopic appendectomy", code.display);
    assertEquals(time, proc.start);
    assertEquals(time + Utilities.convertTime("minutes", 45), proc.stop);
  }

  @Test
  public void gmfTwoObservation() throws Exception {
    Module module = TestHelper.getFixture("gmf_two_point_oh.json");
    State os = module.getState("Observation");
    assertTrue(os.process(person, time));

    HealthRecord.Observation observation = person.record.encounters.get(0).observations.get(0);
    assertEquals("mg/dL", observation.unit);
    double obsValue = (double) observation.value;
    assertTrue(obsValue > 0);
    assertTrue(obsValue < 400);
  }

  @Test
  public void observation() throws Exception {
    Module module = TestHelper.getFixture("observation.json");

    State vitalsign = module.getState("VitalSign");
    assertTrue(vitalsign.process(person, time));
    person.history.add(vitalsign);

    State encounter = module.getState("SomeEncounter");
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    State physiology = module.getState("Simulate_CVS");
    assertTrue(physiology.process(person, time));
    person.history.add(physiology);

    State vitalObs = module.getState("VitalSignObservation");
    assertTrue(vitalObs.process(person, time));

    State codeObs = module.getState("CodeObservation");
    assertTrue(codeObs.process(person, time));

    State sampleObs = module.getState("SampledDataObservation");
    assertTrue(sampleObs.process(person, time));

    State chartObs = module.getState("ChartObservation");
    assertTrue(chartObs.process(person, time));

    State urlObs = module.getState("UrlObservation");
    assertTrue(urlObs.process(person, time));

    HealthRecord.Observation vitalObservation = person.record.encounters.get(0).observations.get(0);
    assertEquals(120.0, vitalObservation.value);
    assertEquals("vital-signs", vitalObservation.category);
    assertEquals("mm[Hg]", vitalObservation.unit);

    Code vitalObsCode = vitalObservation.codes.get(0);
    assertEquals("8480-6", vitalObsCode.code);
    assertEquals("Systolic Blood Pressure", vitalObsCode.display);

    HealthRecord.Observation codeObservation = person.record.encounters.get(0).observations.get(1);
    assertEquals("procedure", codeObservation.category);
    //assertEquals("LOINC", codeObservation.value.system);
    //assertEquals("25428-4", codeObservation.value.code);
    //assertEquals("Glucose [Presence] in Urine by Test strip", codeObservation.value.system);

    Code testCode = new Code("LOINC", "25428-4", "Glucose [Presence] in Urine by Test strip");
    assertEquals(testCode.toString(), codeObservation.value.toString());

    Code codeObsCode = codeObservation.codes.get(0);
    assertEquals("24356-8", codeObsCode.code);
    assertEquals("Urinalysis complete panel - Urine", codeObsCode.display);

    HealthRecord.Observation sampleObservation = person.record.encounters.get(0)
        .observations.get(2);
    assertEquals("procedure", sampleObservation.category);
    assertEquals("mm[Hg]", sampleObservation.unit);
    assertTrue(sampleObservation.value instanceof SampledData);
    SampledData sampledData = (SampledData) sampleObservation.value;
    assertEquals("P_ao", sampledData.attributes.get(0));
    assertEquals("P_lv", sampledData.attributes.get(1));
    assertEquals("P_rv", sampledData.attributes.get(2));
    assertEquals(3, sampledData.series.size());

    HealthRecord.Observation chartObservation = person.record.encounters.get(0).observations.get(3);
    assertTrue(chartObservation.value instanceof Attachment);
    Attachment obsAttachment = (Attachment) chartObservation.value;
    assertEquals("Media Test", obsAttachment.title);
    assertEquals(400, obsAttachment.width);
    assertEquals(200, obsAttachment.height);
    assertEquals("image/png", obsAttachment.contentType);
    assertTrue(Base64.isBase64(obsAttachment.data));

    HealthRecord.Observation urlObservation = person.record.encounters.get(0).observations.get(4);
    assertTrue(urlObservation.value instanceof Attachment);
    Attachment urlAttachment = (Attachment) urlObservation.value;
    assertEquals("Test Image URL", urlAttachment.title);
    assertEquals("66bb1cb31c9b502daa7081ae36631f9df9c6d16a", urlAttachment.hash);
    assertEquals("en-US", urlAttachment.language);
    assertEquals("https://example.com/image/12498596132", urlAttachment.url);
  }

  @Test
  public void imaging_study_during_encounter() throws Exception {
    Module module = TestHelper.getFixture("imaging_study.json");

    // First, onset the injury
    State kneeInjury = module.getState("Knee_Injury");
    assertTrue(kneeInjury.process(person, time));
    person.history.add(kneeInjury);

    // An ImagingStudy must occur during an Encounter
    State encounterState = module.getState("ED_Visit");
    assertTrue(encounterState.process(person, time));
    person.history.add(encounterState);

    // Run the imaging study
    State mri = module.getState("Knee_MRI");
    assertTrue(mri.process(person, time));

    // Verify that the ImagingStudy was added to the record
    HealthRecord.Encounter encounter = person.record.encounters.get(0);

    HealthRecord.ImagingStudy study = encounter.imagingStudies.get(0);
    assertEquals(time, study.start);
    assertEquals(1, study.series.size());

    HealthRecord.ImagingStudy.Series series = study.series.get(0);
    assertEquals(1, series.instances.size());

    Code bodySite = series.bodySite;
    assertEquals("SNOMED-CT", bodySite.system);
    assertEquals("6757004", bodySite.code);
    assertEquals("Right knee", bodySite.display);

    Code modality = series.modality;
    assertEquals("DICOM-DCM", modality.system);
    assertEquals("MR", modality.code);
    assertEquals("Magnetic Resonance", modality.display);

    HealthRecord.ImagingStudy.Instance instance = series.instances.get(0);
    assertEquals("Image of right knee", instance.title);

    Code sopClass = instance.sopClass;
    assertEquals("DICOM-SOP", sopClass.system);
    assertEquals("1.2.840.10008.5.1.4.1.1.4", sopClass.code);
    assertEquals("MR Image Storage", sopClass.display);

    // Verify that the equivalent Procedure was also added to the patient's record
    HealthRecord.Procedure procedure = encounter.procedures.get(0);
    assertEquals(time, procedure.start);

    Code procCode = procedure.codes.get(0);
    assertEquals("2491000087104", procCode.code);
    assertEquals("Magnetic resonance imaging of right knee", procCode.display);
    assertEquals("SNOMED-CT", procCode.system);
  }

  @Test
  public void wellness_encounter() throws Exception {
    Module module = TestHelper.getFixture("encounter.json");
    State encounter = module.getState("Annual_Physical");

    // shouldn't pass through this state until a wellness encounter happens externally
    assertFalse(encounter.process(person, time));

    time = time + Utilities.convertTime("months", 6);
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("weeks", 2);

    simulateWellnessEncounter(module, person, time);

    // Now we should pass through
    assertTrue(encounter.process(person, time));
  }

  @Test
  public void wellness_encounter_diagnoses_condition() throws Exception {
    Module module = TestHelper.getFixture("encounter.json");
    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Now process the encounter, waiting until it actually happens
    State encounter = module.getState("Annual_Physical_2");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);

    simulateWellnessEncounter(module, person, time);

    assertTrue(encounter.process(person, time));

    HealthRecord.Entry condition = person.record.encounters.get(0).conditions.get(0);
    assertEquals(time, condition.start);
    assertEquals(0L, condition.stop);

    Code code = condition.codes.get(0);
    assertEquals("73211009", code.code);
    assertEquals("Diabetes Mellitus", code.display);
  }

  @Test
  public void ed_visit_encounter() throws Exception {
    Module module = TestHelper.getFixture("encounter.json");
    // Non-wellness encounters happen immediately

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    State encounter = module.getState("ED_Visit");
    assertTrue(encounter.process(person, time));
    // Verify that the Encounter was added to the record
    HealthRecord.Encounter enc = person.record.encounters.get(0);
    assertEquals(time, enc.start);
    assertEquals(time + TimeUnit.MINUTES.toMillis(60), enc.stop);

    Code code = enc.codes.get(0);
    assertEquals("50849002", code.code);
    assertEquals("Emergency room admission (procedure)", code.display);
  }

  @Test
  public void encounter_with_attribute_reason() throws Exception {
    Module module = TestHelper.getFixture("encounter.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Non-wellness encounters happen immediately
    State encounter = module.getState("ED_Visit_AttributeReason");
    assertTrue(encounter.process(person, time));
    // Verify that the Encounter was added to the record
    HealthRecord.Encounter enc = person.record.encounters.get(0);
    assertEquals(time, enc.start);
    assertEquals(time + TimeUnit.MINUTES.toMillis(60), enc.stop);
    assertEquals("73211009", enc.reason.code);
    assertEquals("Diabetes Mellitus", enc.reason.display);

    Code code = enc.codes.get(0);
    assertEquals("50849002", code.code);
    assertEquals("Emergency room admission (procedure)", code.display);
  }

  @Test
  public void allergy_onset_during_encounter() throws Exception {
    Module module = TestHelper.getFixture("allergies.json");
    State allergyState = module.getState("Allergy_to_Eggs");
    // Should pass through this state immediately without calling the record
    assertTrue(allergyState.process(person, time));
    person.history.add(allergyState);

    State encounter = module.getState("Dr_Visit");
    assertTrue(encounter.process(person, time));

    HealthRecord.Allergy allergy = person.record.encounters.get(0).allergies.get(0);
    assertEquals(time, allergy.start);
    assertEquals(0L, allergy.stop);

    Code code = allergy.codes.get(0);
    assertEquals("91930004", code.code);
    assertEquals("Allergy to eggs", code.display);

    assertTrue(allergy.reactions.size() >= 1 && allergy.reactions.size() < 3);
    allergy.reactions.forEach((reaction, severity) -> {
      String reactionCode = reaction.code;
      assertTrue(reactionCode.equals("21626009") || reactionCode.equals("91941002"));
      if (reactionCode.equals("21626009")) {
        assertTrue(severity == HealthRecord.ReactionSeverity.MILD
            || severity == HealthRecord.ReactionSeverity.MODERATE);
      }
      if (reactionCode.equals("91941002")) {
        assertTrue(severity == HealthRecord.ReactionSeverity.SEVERE);
      }
    });
  }

  @Test
  public void allergy_end_by_state_name() throws Exception {
    Module module = TestHelper.getFixture("allergies.json");
    State allergyState = module.getState("Allergy_to_Eggs").clone();
    // Should pass through this state immediately without calling the record
    assertTrue(allergyState.process(person, time));
    person.history.add(allergyState);

    State encounter = module.getState("Dr_Visit").clone();
    assertTrue(encounter.process(person, time));

    // Now process the end of the prescription
    State medEnd = module.getState("Allergy_Ends").clone();
    assertTrue(medEnd.process(person, time));

    HealthRecord.Entry allergy = person.record.encounters.get(0).allergies.get(0);
    assertEquals(time, allergy.start);
    assertEquals(time, allergy.stop);

    Code code = allergy.codes.get(0);
    assertEquals("91930004", code.code);
    assertEquals("Allergy to eggs", code.display);
  }

  @Test
  public void condition_end_last_end_time() throws Exception {
    Module module = TestHelper.getFixture("condition_end.json");

    // First, onset the condition
    State condition2 = module.getState("Condition2_Start");
    assertTrue(condition2.process(person, time));
    person.history.add(condition2);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("DiagnosisEncounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    // Simulate the wellness encounter by calling perform_encounter

    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Now process the end of the condition
    State conEnd = module.getState("Condition2_End");
    assertTrue(conEnd.process(person, time));

    Long endTime = person.getOnsetConditionRecord().getConditionLastEndTimeFromModule(
        module.name, "Influenza"
    );
    assertTrue(endTime != null);
    assertEquals(time, endTime.longValue());
  }

  @Test
  public void condition_end_by_entity_attribute() throws Exception {
    Module module = TestHelper.getFixture("condition_end.json");

    // First, onset the condition
    State condition1 = module.getState("Condition1_Start");
    assertTrue(condition1.process(person, time));
    person.history.add(condition1);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("DiagnosisEncounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    HealthRecord.Entry attributeEntry = (HealthRecord.Entry) person.attributes
        .get("Drug Use Behavior");

    // Now process the end of the condition
    State conEnd = module.getState("Condition1_End");
    assertTrue(conEnd.process(person, time));

    HealthRecord.Entry condition = person.record.encounters.get(0).conditions.get(0);
    assertEquals(time, condition.start);
    assertEquals(time, condition.stop);
    assertEquals(attributeEntry, condition);

    Code code = condition.codes.get(0);
    assertEquals("228380004", code.code);
    assertEquals("Chases the dragon (finding)", code.display);

    Long endTime = person.getOnsetConditionRecord().getConditionLastEndTimeFromModule(
        module.name, code.display
    );
    assertTrue(endTime != null);
    assertEquals(time, endTime.longValue());
  }

  @Test
  public void condition_end_by_condition_onset() throws Exception {
    Module module = TestHelper.getFixture("condition_end.json");

    // First, onset the condition
    State condition2 = module.getState("Condition2_Start");
    assertTrue(condition2.process(person, time));
    person.history.add(condition2);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("DiagnosisEncounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    // Simulate the wellness encounter by calling perform_encounter

    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Now process the end of the condition
    State conEnd = module.getState("Condition2_End");
    assertTrue(conEnd.process(person, time));

    HealthRecord.Entry condition = person.record.encounters.get(0).conditions.get(0);
    assertEquals(time, condition.start);
    assertEquals(time, condition.stop);

    Code code = condition.codes.get(0);
    assertEquals("6142004", code.code);
    assertEquals("Influenza", code.display);

    Long endTime = person.getOnsetConditionRecord().getConditionLastEndTimeFromModule(
        module.name, code.display
    );
    assertTrue(endTime != null);
    assertEquals(time, endTime.longValue());
  }

  @Test
  public void condition_end_by_code() throws Exception {
    Module module = TestHelper.getFixture("condition_end.json");

    // First, onset the Diabetes!
    State condition3 = module.getState("Condition3_Start");
    assertTrue(condition3.process(person, time));
    person.history.add(condition3);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("DiagnosisEncounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    // Simulate the wellness encounter by calling perform_encounter
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Now process the end of the condition
    State conEnd = module.getState("Condition3_End");
    assertTrue(conEnd.process(person, time));

    HealthRecord.Entry condition = person.record.encounters.get(0).conditions.get(0);
    assertEquals(time, condition.start);
    assertEquals(time, condition.stop);

    Code code = condition.codes.get(0);
    assertEquals("73211009", code.code);
    assertEquals("Diabetes mellitus", code.display);

    Long endTime = person.getOnsetConditionRecord().getConditionLastEndTimeFromModule(
        module.name, code.display
    );
    assertTrue(endTime != null);
    assertEquals(time, endTime.longValue());
  }

  @Test
  public void medication_order_during_wellness_encounter() throws Exception {
    Module module = TestHelper.getFixture("medication_order.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Prevent Null Pointer by giving the person their QOLS
    Map<Integer, Double> qolsByYear = new HashMap<Integer, Double>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);

    // Now process the prescription
    State med = module.getState("Metformin");
    assertTrue(med.process(person, time));

    // Verify that Metformin was added to the record
    HealthRecord.Medication medication = person.record.encounters.get(0).medications.get(0);
    assertEquals(time, medication.start);
    assertEquals(0L, medication.stop);

    Code code = medication.codes.get(0);
    assertEquals("860975", code.code);
    assertEquals("24 HR Metformin hydrochloride 500 MG Extended Release Oral Tablet", code.display);
  }

  @Test
  public void medication_order_with_dosage() throws Exception {
    Module module = TestHelper.getFixture("medication_order.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Prevent Null Pointer by giving the person their QOLS
    Map<Integer, Double> qolsByYear = new HashMap<Integer, Double>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);

    // Now process the prescription
    State med = module.getState("Metformin_With_Dosage");
    assertTrue(med.process(person, time));

    // Verify that Metformin was added to the record, including dosage information
    HealthRecord.Medication medication = person.record.encounters.get(0).medications.get(0);
    assertEquals(time, medication.start);
    assertEquals(0L, medication.stop);
    // TODO: verify details. ideally these should not just be a jsonobject
    Code code = medication.codes.get(0);
    assertEquals("860975", code.code);
    assertEquals("24 HR Metformin hydrochloride 500 MG Extended Release Oral Tablet", code.display);
    assertNotNull(medication.prescriptionDetails);
  }

  @Test
  public void medication_order_as_needed() throws Exception {
    Module module = TestHelper.getFixture("medication_order.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);

    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Prevent Null Pointer by giving the person their QOLS
    Map<Integer, Double> qolsByYear = new HashMap<Integer, Double>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);

    // Now process the prescription
    State med = module.getState("Tylenol_As_Needed");
    assertTrue(med.process(person, time));

    // Verify that tylenol was added to the record
    HealthRecord.Medication medication = person.record.encounters.get(0).medications.get(0);
    assertEquals(time, medication.start);
    assertEquals(0L, medication.stop);
    // TODO: verify details. ideally these should not just be a jsonobject

    Code code = medication.codes.get(0);
    assertEquals("123456", code.code);
    assertEquals("Acetaminophen 325mg [Tylenol]", code.display);
  }

  @Test
  public void medication_order_assigns_administered_attribute() throws Exception {
    person.attributes.remove("Diabetes Medication");
    Module module = TestHelper.getFixture("medication_order.json");
    State encounter = module.getState("Wellness_Encounter");
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    State med = module.getState("Metformin_With_Administration");
    assertTrue(med.process(person, time));

    HealthRecord.Medication medication = (HealthRecord.Medication) person.attributes
        .get("Diabetes Medication");
    assertTrue(medication.administration);
    assertTrue(medication.stop != 0L);
  }

  @Test
  public void medication_order_assigns_entity_attribute() throws Exception {
    person.attributes.remove("Diabetes Medication");
    Module module = TestHelper.getFixture("medication_order.json");
    State encounter = module.getState("Wellness_Encounter");
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    State med = module.getState("Metformin");
    assertTrue(med.process(person, time));

    HealthRecord.Medication medication =
        (HealthRecord.Medication) person.attributes.get("Diabetes Medication");
    assertEquals(time, medication.start);

    assertFalse(medication.administration);

    Code code = medication.codes.get(0);
    assertEquals("860975", code.code);
    assertEquals("24 HR Metformin hydrochloride 500 MG Extended Release Oral Tablet", code.display);
  }

  @Test
  public void medication_end_by_entity_attribute() throws Exception {
    Module module = TestHelper.getFixture("medication_end.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);

    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Prevent Null Pointer by giving the person their QOLS
    Map<Integer, Double> qolsByYear = new HashMap<Integer, Double>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);

    // Now process the prescription
    State med = module.getState("Insulin_Start");
    assertTrue(med.process(person, time));

    person.history.add(med);

    // Now process the end of the prescription
    State medEnd = module.getState("Insulin_End");
    assertTrue(medEnd.process(person, time));

    HealthRecord.Medication medication = person.record.encounters.get(0).medications.get(0);
    assertEquals(time, medication.start);
    assertEquals(time, medication.stop);

    Code code = medication.codes.get(0);
    assertEquals("575679", code.code);
    assertEquals("Insulin, Aspart, Human 100 UNT/ML [NovoLOG]", code.display);
  }

  @Test
  public void medication_end_by_medication_order() throws Exception {
    Module module = TestHelper.getFixture("medication_end.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Prevent Null Pointer by giving the person their QOLS
    Map<Integer, Double> qolsByYear = new HashMap<Integer, Double>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);

    // Now process the prescription
    State med = module.getState("Bromocriptine_Start");
    assertTrue(med.process(person, time));

    person.history.add(med);

    // Now process the end of the prescription
    State medEnd = module.getState("Bromocriptine_End");
    assertTrue(medEnd.process(person, time));

    HealthRecord.Medication medication = person.record.encounters.get(0).medications.get(0);
    assertEquals(time, medication.start);
    assertEquals(time, medication.stop);

    Code code = medication.codes.get(0);
    assertEquals("563894", code.code);
    assertEquals("Bromocriptine 5 MG [Parlodel]", code.display);
  }

  @Test
  public void medication_end_by_code() throws Exception {
    Module module = TestHelper.getFixture("medication_end.json");

    // First, onset the Diabetes!
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Prevent Null Pointer by giving the person their QOLS
    Map<Integer, Double> qolsByYear = new HashMap<Integer, Double>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);

    // Now process the prescription
    State med = module.getState("Metformin_Start");
    assertTrue(med.process(person, time));

    person.history.add(med);

    // Now process the end of the prescription
    State medEnd = module.getState("Metformin_End");
    assertTrue(medEnd.process(person, time));

    HealthRecord.Medication medication = person.record.encounters.get(0).medications.get(0);
    assertEquals(time, medication.start);
    assertEquals(time, medication.stop);

    Code code = medication.codes.get(0);
    assertEquals("860975", code.code);
    assertEquals("24 HR Metformin hydrochloride 500 MG Extended Release Oral Tablet", code.display);
  }

  @Test
  public void careplan_start() throws Exception {
    Module module = TestHelper.getFixture("careplan_start.json");

    // First onset diabetes
    State diabetes = module.getState("Diabetes");
    assertTrue(diabetes.process(person, time));
    person.history.add(diabetes);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Now process the careplan
    State plan = module.getState("Diabetes_Self_Management");
    assertTrue(plan.process(person, time));
    person.history.add(plan);

    // Verify that the careplan was added to the record
    HealthRecord.CarePlan cp = person.record.encounters.get(0).careplans.get(0);
    assertEquals(time, cp.start);
    assertEquals(0L, cp.stop);

    Code code = cp.codes.get(0);
    assertEquals("698360004", code.code);
    assertEquals("Diabetes self management plan", code.display);

    assertEquals(1, cp.activities.size());
    Code activity = cp.activities.iterator().next();
    assertEquals("160670007", activity.code);
    assertEquals("Diabetic diet", activity.display);
  }

  @Test
  public void careplan_assigns_entity_attribute() throws Exception {
    person.attributes.remove("Diabetes_CarePlan");
    Module module = TestHelper.getFixture("careplan_start.json");
    State encounter = module.getState("Wellness_Encounter");
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    State plan = module.getState("Diabetes_Self_Management");
    assertTrue(plan.process(person, time));

    HealthRecord.CarePlan cp = (HealthRecord.CarePlan) person.attributes.get("Diabetes_CarePlan");
    assertEquals(time, cp.start);
    assertEquals(0L, cp.stop);

    Code code = cp.codes.get(0);
    assertEquals("698360004", code.code);
    assertEquals("Diabetes self management plan", code.display);
  }

  @Test
  public void careplan_end_by_entity_attribute() throws Exception {
    Module module = TestHelper.getFixture("careplan_end.json");

    // First, onset the condition
    State condition = module.getState("The_Condition");
    assertTrue(condition.process(person, time));
    person.history.add(condition);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Now process the careplan
    State plan = module.getState("CarePlan1_Start");
    // have to use `run` not `process` here because the entity
    // attribute stuff happens in `run`
    assertTrue(plan.process(person, time));
    person.history.add(plan);

    HealthRecord.CarePlan entityAttribute = (HealthRecord.CarePlan) person.attributes
        .get("Diabetes_CarePlan");

    // Now process the end of the careplan
    State planEnd = module.getState("CarePlan1_End");
    assertTrue(planEnd.process(person, time));
    person.history.add(planEnd);

    HealthRecord.CarePlan cp = person.record.encounters.get(0).careplans.get(0);
    assertEquals(time, cp.start);
    assertEquals(time, cp.stop);
    assertEquals(cp, entityAttribute);

    Code code = cp.codes.get(0);
    assertEquals("698360004", code.code);
    assertEquals("Diabetes self management plan", code.display);

    assertEquals(1, cp.activities.size());
    Code activity = cp.activities.iterator().next();
    assertEquals("160670007", activity.code);
    assertEquals("Diabetic diet", activity.display);
  }

  @Test
  public void careplan_end_by_code() throws Exception {
    Module module = TestHelper.getFixture("careplan_end.json");

    // First, onset the condition
    State condition = module.getState("The_Condition");
    assertTrue(condition.process(person, time));
    person.history.add(condition);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    person.history.add(encounter);

    // Now process the careplan
    State plan = module.getState("CarePlan2_Start");
    assertTrue(plan.process(person, time));
    person.history.add(plan);

    // Now process the end of the careplan
    State planEnd = module.getState("CarePlan2_End");
    assertTrue(planEnd.process(person, time));
    person.history.add(planEnd);

    HealthRecord.CarePlan cp = person.record.encounters.get(0).careplans.get(0);
    assertEquals(time, cp.start);
    assertEquals(time, cp.stop);

    Code code = cp.codes.get(0);
    assertEquals("698358001", code.code);
    assertEquals("Angina self management plan", code.display);

    assertEquals(2, cp.activities.size());
    Iterator<Code> itr = cp.activities.iterator();
    // note that activities is a LinkedHashSet so should preserve insertion order
    Code activity = itr.next();
    assertEquals("229065009", activity.code);
    assertEquals("Exercise therapy", activity.display);

    activity = itr.next();
    assertEquals("226234005", activity.code);
    assertEquals("Healthy diet", activity.display);
  }

  @Test
  public void careplan_end_by_careplan() throws Exception {
    Module module = TestHelper.getFixture("careplan_end.json");

    // First, onset the condition
    State condition = module.getState("The_Condition");
    assertTrue(condition.process(person, time));
    person.history.add(condition);

    // Process the wellness encounter state, which will wait for a wellness encounter
    State encounter = module.getState("Wellness_Encounter");
    assertFalse(encounter.process(person, time));
    time = time + Utilities.convertTime("months", 6);
    simulateWellnessEncounter(module, person, time);
    assertTrue(encounter.process(person, time));
    person.history.add(encounter);

    // Now process the careplan
    State plan = module.getState("CarePlan3_Start");
    assertTrue(plan.process(person, time));
    person.history.add(plan);

    // Now process the end of the careplan
    State planEnd = module.getState("CarePlan3_End");
    assertTrue(planEnd.process(person, time));
    person.history.add(planEnd);

    HealthRecord.CarePlan cp = person.record.encounters.get(0).careplans.get(0);
    assertEquals(time, cp.start);
    assertEquals(time, cp.stop);

    Code code = cp.codes.get(0);
    assertEquals("408907000", code.code);
    assertEquals("Immunological care management", code.display);

    assertEquals(1, cp.activities.size());
    Code activity = cp.activities.iterator().next();
    assertEquals("182678001", activity.code);
    assertEquals("Hyposensitization to allergens (procedure)", activity.display);
  }

  @Test
  public void death() throws Exception {
    Module module = TestHelper.getFixture("death.json");
    State death = module.getState("Death");
    assertTrue(person.alive(time));
    assertTrue(death.process(person, time));

    // Patient shouldn't be alive anymore
    assertFalse(person.alive(time));

    // Verify that death was added to the record
    assertEquals(time, (long) person.record.death);
  }

  @Test
  public void future_death() throws Exception {
    Module module = TestHelper.getFixture("death_life_expectancy.json");
    module.process(person, time);
    module.process(person, time + Utilities.convertTime("days", 7));

    assertTrue(person.alive(time + Utilities.convertTime("days", 7)));
    assertTrue((boolean) person.attributes.get("processing"));
    assertNull(person.attributes.get("still_processing"));

    module.process(person, time + Utilities.convertTime("days", 14));
    assertTrue((boolean) person.attributes.get("still_processing"));

    module.process(person, time + Utilities.convertTime("months", 6));
    assertFalse(person.alive(time + Utilities.convertTime("months", 6)));
  }

  @Test
  public void the_dead_should_stay_dead() throws Exception {
    // Load all the static modules used in Generator.java
    EncounterModule encounterModule = new EncounterModule();

    List<Module> modules = new ArrayList<Module>();
    modules.add(new LifecycleModule());
    modules.add(new CardiovascularDiseaseModule());
    modules.add(new QualityOfLifeModule());
    // modules.add(new HealthInsuranceModule());
    modules.add(new WeightLossModule());
    // Make sure the patient dies...
    modules.add(TestHelper.getFixture("death_life_expectancy.json"));
    // And make sure the patient has weird delays that are between timesteps...
    modules.add(Module.getModuleByPath("dialysis"));

    // Set life signs at birth...
    long timeT = (long) person.attributes.get(Person.BIRTHDATE);
    LifecycleModule.birth(person, timeT);
    // Make sure the patient requires dialysis to use that module's
    // repeating delayed encounters...
    person.attributes.put("ckd", 5);

    long timestep = Long.parseLong(Config.get("generate.timestep"));
    long stop = time;
    while (person.alive(timeT) && timeT < stop) {
      encounterModule.process(person, timeT);
      Iterator<Module> iter = modules.iterator();
      while (iter.hasNext()) {
        Module module = iter.next();
        if (module.process(person, timeT)) {
          iter.remove(); // this module has completed/terminated.
        }
      }
      encounterModule.endEncounterModuleEncounters(person, timeT);

      timeT += timestep;
    }
    DeathModule.process(person, time);

    // Now check that the person stayed dead...
    long deathTime = (Long) person.attributes.get(Person.DEATHDATE);
    for (Encounter encounter : person.record.encounters) {
      if (!encounter.codes.contains(DeathModule.DEATH_CERTIFICATION)) {
        assertTrue(encounter.start < deathTime);
      }
    }
  }

  @Test
  public void the_dead_should_stay_dead_forever() throws Exception {
    Module module = TestHelper.getFixture("death_life_expectancy.json");

    long timestep = Long.parseLong(Config.get("generate.timestep"));
    long timeT = time;
    while (person.alive(timeT)) {
      module.process(person, timeT);
      timeT += timestep;
    }

    // Now check that the person stayed dead...
    long deathTime = (Long) person.attributes.get(Person.DEATHDATE);
    for (Encounter encounter : person.record.encounters) {
      if (!encounter.codes.contains(DeathModule.DEATH_CERTIFICATION)) {
        assertTrue(encounter.start < deathTime);
      }
    }
  }

  @Test
  public void cause_of_death_code() throws Exception {
    Module module = TestHelper.getFixture("death_reason.json");

    // First, onset the Diabetes!
    State condition = module.getState("OnsetDiabetes");
    assertTrue(condition.process(person, time));
    person.history.add(condition);

    // Now process the end of the condition
    State death = module.getState("Death_by_Code");
    assertTrue(death.process(person, time));

    assertFalse(person.alive(time));
  }

  @Test
  public void cause_of_death_conditionOnset() throws Exception {
    Module module = TestHelper.getFixture("death_reason.json");

    // First, onset the Diabetes!
    State condition = module.getState("OnsetDiabetes");
    assertTrue(condition.process(person, time));
    person.history.add(condition);

    // Now process the end of the condition
    State death = module.getState("Death_by_ConditionOnset");
    assertTrue(death.process(person, time));

    assertFalse(person.alive(time));
  }

  @Test
  public void cause_of_death_attribute() throws Exception {
    Module module = TestHelper.getFixture("death_reason.json");

    // First, onset the Diabetes!
    State condition = module.getState("OnsetDiabetes");
    assertTrue(condition.process(person, time));
    person.history.add(condition);

    // Now process the end of the condition
    State death = module.getState("Death_by_Attribute");
    assertTrue(death.process(person, time));

    assertFalse(person.alive(time));
  }

  @Test
  public void testDelayRewindTime() throws Exception {
    // Synthea is currently run in 7-day increments. If a delay falls between increments, then the
    // delay and subsequent states must be run at the delay expiration time -- not at the current
    // cycle time.

    // Setup the context
    Module module = TestHelper.getFixture("delay_time_travel.json");

    // Run number one should stop at the delay
    module.process(person, time);
    assertEquals("2_Day_Delay", person.history.get(0).name);

    // Run number two should go all the way to Terminal, but should process Encounter and Death
    // along the way
    // Run number 2: 7 days after run number 1
    module.process(person, time + days(7));


    assertEquals(6, person.history.size());
    assertEquals("Initial", person.history.get(5).name);
    assertEquals(time, (long)person.history.get(5).entered);
    assertEquals(time, (long) person.history.get(5).exited);

    assertEquals("2_Day_Delay", person.history.get(4).name);
    assertEquals(time, (long) person.history.get(4).entered);
    assertEquals(time + days(2), (long) person.history.get(4).exited);

    assertEquals("ED_Visit", person.history.get(3).name);
    assertEquals(time + days(2), (long) person.history.get(3).entered);
    assertEquals(time + days(2), (long) person.history.get(3).exited);

    assertEquals("3_Day_Delay", person.history.get(2).name);
    assertEquals(time + days(2), (long) person.history.get(2).entered);
    assertEquals(time + days(5), (long) person.history.get(2).exited);

    assertEquals("Death", person.history.get(1).name);
    assertEquals(time + days(5), (long) person.history.get(1).entered);
    assertEquals(time + days(5), (long) person.history.get(1).exited);

    assertEquals("Terminal", person.history.get(0).name);
    assertEquals(null, person.history.get(0).entered);
    assertEquals(null, person.history.get(0).exited);
  }

  /**
   * Readability helper for the above test case. Turn days into time.
   * @param numDays Number of days
   * @return Amount to add to timestamp
   */
  private static long days(long numDays) {
    return Utilities.convertTime("days", numDays);
  }

  @Test
  public void testSubmoduleHistory() throws Exception {
    Map<String, Module.ModuleSupplier> modules =
            Whitebox.<Map<String, Module.ModuleSupplier>>getInternalState(Module.class, "modules");
    // hack to load these test modules so they can be called by the CallSubmodule state
    Module subModule1 = TestHelper.getFixture("submodules/encounter_submodule.json");
    subModule1.submodule = true;
    Module subModule2 = TestHelper.getFixture("submodules/medication_submodule.json");
    subModule2.submodule = true;
    modules.put("submodules/encounter_submodule", new Module.ModuleSupplier(subModule1));
    modules.put("submodules/medication_submodule", new Module.ModuleSupplier(subModule2));

    try {
      Module module = TestHelper.getFixture("recursively_calls_submodules.json");
      while (!module.process(person, time)) {
        time += Utilities.convertTime("years", 1);
      }

      // main module has 5 states, with the callsubmodule counted 2x
      // encounter_submodule has 6 states, with the callsubmodule counted 2x
      // medication_submodule has 5 states
      // total = 18
      System.out.println(person.history);
      assertEquals(18, person.history.size());

      assertEquals("Initial", person.history.get(17).name);
      assertEquals("Recursive Calls Submodules Module", person.history.get(17).module.name);

      assertEquals("Example_Condition", person.history.get(16).name);
      assertEquals("Recursive Calls Submodules Module", person.history.get(16).module.name);
      long previousStateExited = person.history.get(17).exited;
      long currentStateEntered = person.history.get(16).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Call_Encounter_Submodule", person.history.get(15).name);
      assertEquals("Recursive Calls Submodules Module", person.history.get(15).module.name);
      previousStateExited = person.history.get(16).exited;
      currentStateEntered = person.history.get(15).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Initial", person.history.get(14).name);
      assertEquals("Encounter Submodule Module", person.history.get(14).module.submoduleName);
      // the state being called by the submodule won't necessarily have this property
      // because submodule.exited gets rewritten to whenever the terminal of the submodule exits
      // ex. main.CallSubmodule: entered 1/1/2020, exited 12/31/2020
      //     sub.Initial: entered 1/1/2020 exited 1/1/2020
      //     sub.Delay: entered 1/1/2020 exited 12/31/2020
      //     sub.Terminal: entered 12/31/2020 exited 12/31/2020
      // previousStateExited = person.history.get(15).exited;
      // currentStateEntered = person.history.get(14).entered;
      // assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Delay", person.history.get(13).name);
      assertEquals("Encounter Submodule Module", person.history.get(13).module.submoduleName);
      previousStateExited = person.history.get(14).exited;
      currentStateEntered = person.history.get(13).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Encounter_In_Submodule", person.history.get(12).name);
      assertEquals("Encounter Submodule Module", person.history.get(12).module.submoduleName);
      previousStateExited = person.history.get(13).exited;
      currentStateEntered = person.history.get(12).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Call_MedicationOrder_Submodule", person.history.get(11).name);
      assertEquals("Encounter Submodule Module", person.history.get(11).module.submoduleName);
      previousStateExited = person.history.get(12).exited;
      currentStateEntered = person.history.get(11).entered;
      assertEquals(previousStateExited, currentStateEntered);


      assertEquals("Initial", person.history.get(10).name);
      assertEquals("Medication Submodule Module", person.history.get(10).module.submoduleName);
      // previousStateExited = person.history.get(11).exited;
      // currentStateEntered = person.history.get(10).entered;
      // assertEquals(previousStateExited, currentStateEntered);
      // see notes above for why this property doesn't hold

      assertEquals("Examplitis_Medication", person.history.get(9).name);
      assertEquals("Medication Submodule Module", person.history.get(9).module.submoduleName);
      previousStateExited = person.history.get(10).exited;
      currentStateEntered = person.history.get(9).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Delay_Yet_Again", person.history.get(8).name);
      assertEquals("Medication Submodule Module", person.history.get(8).module.submoduleName);
      previousStateExited = person.history.get(9).exited;
      currentStateEntered = person.history.get(8).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("End_Medication", person.history.get(7).name);
      assertEquals("Medication Submodule Module", person.history.get(7).module.submoduleName);
      previousStateExited = person.history.get(8).exited;
      currentStateEntered = person.history.get(7).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Med_Terminal", person.history.get(6).name);
      assertEquals("Medication Submodule Module", person.history.get(6).module.submoduleName);
      previousStateExited = person.history.get(7).exited;
      currentStateEntered = person.history.get(6).entered;
      assertEquals(previousStateExited, currentStateEntered);


      assertEquals("Call_MedicationOrder_Submodule", person.history.get(5).name);
      assertEquals("Encounter Submodule Module", person.history.get(5).module.submoduleName);
      // previousStateExited = person.history.get(6).exited;
      // currentStateEntered = person.history.get(5).entered;
      // assertEquals(previousStateExited, currentStateEntered);
      // note that this is the same state object at position 11 and 5
      // even though it was only called once it shows up twice
      // (before and after all of the submodule states)
      // but the entered & exited times don't line up cleanly

      assertEquals("Delay_Some_More", person.history.get(4).name);
      assertEquals("Encounter Submodule Module", person.history.get(4).module.submoduleName);
      previousStateExited = person.history.get(5).exited;
      currentStateEntered = person.history.get(4).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Encounter_Terminal", person.history.get(3).name);
      assertEquals("Encounter Submodule Module", person.history.get(3).module.submoduleName);
      previousStateExited = person.history.get(4).exited;
      currentStateEntered = person.history.get(3).entered;
      assertEquals(previousStateExited, currentStateEntered);


      assertEquals("Call_Encounter_Submodule", person.history.get(2).name);
      assertEquals("Recursive Calls Submodules Module", person.history.get(2).module.name);
      // previousStateExited = person.history.get(3).exited;
      // currentStateEntered = person.history.get(2).entered;
      // assertEquals(previousStateExited, currentStateEntered);
      // see note above, this is the same as position 15

      assertEquals("End_Condition", person.history.get(1).name);
      assertEquals("Recursive Calls Submodules Module", person.history.get(1).module.name);
      previousStateExited = person.history.get(2).exited;
      currentStateEntered = person.history.get(1).entered;
      assertEquals(previousStateExited, currentStateEntered);

      assertEquals("Terminal", person.history.get(0).name);
      assertEquals("Recursive Calls Submodules Module", person.history.get(0).module.name);
      previousStateExited = person.history.get(1).exited;
      currentStateEntered = person.history.get(0).entered;
      assertEquals(previousStateExited, currentStateEntered);

    } finally {
      // always clean these up, to ensure they don't get seen by any other tests
      modules.remove("submodules/encounter_submodule");
      modules.remove("submodules/medication_submodule");
    }
  }

  @Test
  public void testSubmoduleDiagnosesAndEndingEncounters() throws Exception {
    Map<String, Module.ModuleSupplier> modules =
        Whitebox.<Map<String, Module.ModuleSupplier>>getInternalState(Module.class, "modules");
    // hack to load these test modules so they can be called by the CallSubmodule state
    Module subModule = TestHelper.getFixture("submodules/admission.json");
    subModule.submodule = true;
    modules.put("submodules/admission", new Module.ModuleSupplier(subModule));

    try {
      Module module = TestHelper.getFixture("encounter_with_submodule.json");
      while (!module.process(person, time)) {
        time += Utilities.convertTime("years", 1);
      }

      assertEquals(12, person.history.size());
      assertEquals(2, person.record.encounters.size());
      assertEquals(1, person.record.encounters.get(0).conditions.size());
      assertEquals(EncounterType.AMBULATORY.toString().toLowerCase(),
          person.record.encounters.get(0).type);
      assertEquals(EncounterType.INPATIENT.toString().toLowerCase(),
          person.record.encounters.get(1).type);
      // Fake code for condition in the submodule "admission"
      assertTrue(person.record.conditionActive("5678"));
    } finally {
      // always clean these up, to ensure they don't get seen by any other tests
      modules.remove("submodules/admission");
    }
  }

  @Test
  public void testDiagnosticReport() throws Exception {
    // Birth makes the vital signs come alive :-)
    LifecycleModule.birth(person, (long)person.attributes.get(Person.BIRTHDATE));

    Module module = TestHelper.getFixture("observation_groups.json");

    State condition = module.getState("Record_MetabolicPanel");
    assertTrue(condition.process(person, time));

    // for a DiagnosticReport, we expect the report as well as the individual observations
    // to be added to the record
    Encounter e = person.record.encounters.get(0);

    assertEquals(1, e.reports.size());
    HealthRecord.Report report = e.reports.get(0);
    assertEquals(8, report.observations.size());
    assertEquals(8, e.observations.size());

    String[] codes =
        {"2339-0", "6299-2", "38483-4", "49765-1", "2947-0", "6298-4", "2069-3", "20565-8"};
    // Glucose, Urea Nitrogen, Creatinine, Calcium, Sodium, Potassium, Chloride, Carbon Dioxide

    for (int i = 0; i < 8; i++) {
      HealthRecord.Observation o = e.observations.get(i);

      assertEquals(codes[i], o.codes.get(0).code);
      assertEquals(report, o.report);
    }
  }

  @Test
  public void testMultiObservation() throws Exception {
    // Birth makes the blood pump :-)
    LifecycleModule.birth(person, (long)person.attributes.get(Person.BIRTHDATE));

    Module module = TestHelper.getFixture("observation_groups.json");

    State condition = module.getState("Record_BP");
    assertTrue(condition.process(person, time));

    // for a MultiObservation, we expect only the MultiObs to be added to the record,
    // not the child observations, which get added as components of the parent observation
    Encounter e = person.record.encounters.get(0);
    assertEquals(1, e.observations.size());

    HealthRecord.Observation o = e.observations.get(0);
    assertEquals("55284-4", o.codes.get(0).code);
    assertEquals(2, o.observations.size());
    assertEquals("8462-4", o.observations.get(0).codes.get(0).code); // diastolic
    assertEquals("8480-6", o.observations.get(1).codes.get(0).code); // systolic
  }

  @Test
  public void testPhysiology() throws Exception {

    // BMI is an input parameter so we need to set it
    person.setVitalSign(VitalSign.BMI, 32.98);

    // Pulmonary resistance and BMI multiplier are also input parameters
    person.attributes.put("Pulmonary Resistance", 0.1552);
    person.attributes.put("BMI Multiplier", 0.055);

    Module module = TestHelper.getFixture("smith_physiology.json");

    State simulateCvs = module.getState("Simulate_CVS");
    assertTrue(simulateCvs.process(person, time));

    // The "Final Aortal Volume" attribute should have been set
    assertTrue(person.attributes.containsKey("Final Aortal Volume"));

    // The "Arterial Pressure Values" attribute should have been set to a list
    assertTrue(person.attributes.get("Arterial Pressure Values") instanceof TimeSeriesData);

    // LVEF should be diminished and BP should be elevated
    assertTrue("LVEF < 60%", (double) person.attributes.get("LVEF") < 60.0);
    assertTrue("LVEF > 50%", (double) person.attributes.get("LVEF") > 50.0);
    assertTrue("SYS BP < 150 mmhg",
        (double) person.attributes.get("SBP") < 150.0);
    assertTrue("SYS BP > 130 mmhg",
        (double) person.attributes.get("SBP") > 130.0);
    assertTrue("DIA BP < 100 mmhg",
        (double) person.attributes.get("DBP") < 100.0);
    assertTrue("DIA BP > 80 mmhg",
        (double) person.attributes.get("DBP") > 80.0);

    // test that the state can be effectively cloned
    State cvsClone = simulateCvs.clone();

    assertNotEquals(cvsClone, simulateCvs);
    assertTrue(cvsClone.process(person, time));

  }

  @Test
  public void testPhysiologyDisabled() throws Exception {

    // Ensure state is disabled
    State.ENABLE_PHYSIOLOGY_STATE = false;

    Module module = TestHelper.getFixture("smith_physiology.json");

    // Run the whole module against the Person
    try {
      module.process(person, 0L);
      fail("Expected a RuntimeException to be thrown");
    } catch (RuntimeException ex) {
      // The module doesn't set "Arterial Pressure Values" when Physiology states
      // are disabled so we should get an exception
      assertEquals("Invalid Person attribute \"Arterial Pressure Values\" "
          + "provided for chart series: null. Attribute value must be a "
          + "TimeSeriesData or List<Double> Object.", ex.getMessage());
    }

    // Values should have been set directly instead of through the simulation
    assertEquals(55.5, (double) person.attributes.get("LVEF"), 0.0001);
    assertEquals(140.5, (double) person.attributes.get("SBP"), 0.0001);
    assertEquals(90.5, (double) person.attributes.get("DBP"), 0.0001);

    // Re-enable physiology states
    State.ENABLE_PHYSIOLOGY_STATE = true;
  }

  @Test
  public void testExpressionUse() throws Exception {

    // Birth makes the vital signs come alive :-)
    LifecycleModule.birth(person, (long)person.attributes.get(Person.BIRTHDATE));

    Module module = TestHelper.getFixture("expression_use.json");

    State attrExpression = module.getState("Set_Attr");
    assertTrue(attrExpression.process(person, time));

    State vitalExpression = module.getState("Set_Vital");
    assertTrue(vitalExpression.process(person, time));

    State observeExpression = module.getState("Observe");
    assertTrue(observeExpression.process(person, time));

    // Verify that the Person now has an LVEF value of 60
    assertEquals(person.getVitalSign(VitalSign.LVEF, time), 60.0, 0.00001);

  }

  @Test
  public void testDevice() throws Exception {
    Module module = TestHelper.getFixture("artificial_heart_device.json");

    State encounterState = module.getState("Encounter");
    assertTrue(encounterState.process(person, time));

    State deviceState = module.getState("Artificial_Heart");
    assertTrue(deviceState.process(person, time));

    Encounter encounter = person.record.currentEncounter(time);
    List<HealthRecord.Device> devices = encounter.devices;
    assertNotNull(devices);
    assertEquals(1, devices.size());

    HealthRecord.Device device = devices.get(0);
    assertEquals("13459008", device.type);
    assertEquals("SynCardia", device.manufacturer);
    assertEquals("Total Artificial Heart", device.model);
    assertEquals(0L, device.stop);

    HealthRecord.Device attribute = (HealthRecord.Device)person.attributes.get("artificial_heart");
    assertNotNull(attribute);
    assertTrue(attribute == device); // we want reference equality, it should be the same object
  }

  @Test
  public void testDeviceEndByAttribute() throws Exception {
    Module module = TestHelper.getFixture("artificial_heart_device.json");

    State encounterState = module.getState("Encounter");
    assertTrue(encounterState.process(person, time));

    State deviceState = module.getState("Artificial_Heart");
    assertTrue(deviceState.process(person, time));

    State deviceEndState = module.getState("Remove_Device_By_Attribute");
    assertTrue(deviceEndState.process(person, time));

    Encounter encounter = person.record.currentEncounter(time);
    List<HealthRecord.Device> devices = encounter.devices;
    HealthRecord.Device device = devices.get(0);
    assertEquals(time, device.stop);
  }

  @Test
  public void testDeviceEndByCode() throws Exception {
    Module module = TestHelper.getFixture("artificial_heart_device.json");

    State encounterState = module.getState("Encounter");
    assertTrue(encounterState.process(person, time));

    State deviceState = module.getState("Artificial_Heart");
    assertTrue(deviceState.process(person, time));

    State deviceEndState = module.getState("Remove_Device_By_Code");
    assertTrue(deviceEndState.process(person, time));

    Encounter encounter = person.record.currentEncounter(time);
    List<HealthRecord.Device> devices = encounter.devices;
    HealthRecord.Device device = devices.get(0);
    assertEquals(time, device.stop);
  }


  @Test
  public void testDeviceEndByState() throws Exception {
    Module module = TestHelper.getFixture("artificial_heart_device.json");

    State encounterState = module.getState("Encounter");
    assertTrue(encounterState.process(person, time));

    State deviceState = module.getState("Artificial_Heart");
    assertTrue(deviceState.process(person, time));

    State deviceEndState = module.getState("Remove_Device_By_State");
    assertTrue(deviceEndState.process(person, time));

    Encounter encounter = person.record.currentEncounter(time);
    List<HealthRecord.Device> devices = encounter.devices;
    HealthRecord.Device device = devices.get(0);
    assertEquals(time, device.stop);
  }

  @Test
  public void testSupplyList() throws Exception {
    Module module = TestHelper.getFixture("artificial_heart_device.json");

    State encounterState = module.getState("Encounter");
    assertTrue(encounterState.process(person, time));

    State supplyListState = module.getState("Necessary_Supplies");
    assertTrue(supplyListState.process(person, time));

    Encounter encounter = person.record.currentEncounter(time);
    List<HealthRecord.Supply> supplies = encounter.supplies;
    assertNotNull(supplies);
    assertEquals(4, supplies.size());

    String[] expectedCodes = { "52291003", "468159004", "39802000", "788177008" };
    String[] expectedDisplays = { "Glove, device (physical object)",
        "Cotton ball (physical object)", "Tongue blade, device (physical object)",
        "Examination gown, single-use (physical object)" };
    int[] expectedQuantities = { 10_000, 3_000, 98765, 1 };

    for (int i = 0; i < 4; i++) {
      HealthRecord.Supply supply = supplies.get(i);
      Code code = supply.codes.get(0);
      assertEquals(expectedCodes[i], code.code);
      assertEquals(expectedDisplays[i], code.display);
      assertEquals(expectedQuantities[i], supply.quantity);
    }
  }

  @Test
  public void testVaccine() throws Exception {
    Module module = TestHelper.getFixture("vaccine.json");

    State encounterState = module.getState("Encounter");
    assertTrue(encounterState.process(person, time));

    State supplyListState = module.getState("Vaccine");
    assertTrue(supplyListState.process(person, time));

    Encounter encounter = person.record.currentEncounter(time);
    List<HealthRecord.Immunization> vaccines = encounter.immunizations;
    assertNotNull(vaccines);
    assertEquals(1, vaccines.size());

    String[] expectedCodes = { "123" };
    String[] expectedDisplays = { "CVX Vaccine Code" };

    for (int i = 0; i < vaccines.size(); i++) {
      HealthRecord.Immunization vaccine = vaccines.get(i);
      Code code = vaccine.codes.get(0);
      assertEquals(expectedCodes[i], code.code);
      assertEquals(expectedDisplays[i], code.display);
    }
  }
}
