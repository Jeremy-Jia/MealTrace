package dev.mealtrace.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class NutritionTest {
  Nutrition.Profile profile() {
    Nutrition.Profile p = new Nutrition.Profile();
    p.age = 35;
    p.height = 175;
    p.weight = 85;
    p.male = true;
    p.activity = 1.2;
    return p;
  }

  Nutrition.Total meal(double lo, double hi, boolean confirmed) {
    Nutrition.Total t = new Nutrition.Total();
    t.add((lo + hi) / 2, lo, hi, 30, 60, 20, 1, confirmed, true);
    return t;
  }

  @Test
  public void missingProfileNeverGivesBudget() {
    assertNull(
        Nutrition.advice(new Nutrition.Profile(), meal(400, 600, true), true, false, 13)
            .snackBudget());
  }

  @Test
  public void specialCircumstancesDisableAutomaticTarget() {
    Nutrition.Profile p = profile();
    p.special = true;
    assertEquals(0, p.target(), 0);
    assertNull(Nutrition.advice(p, meal(400, 600, true), true, true, 23).snackBudget());
  }

  @Test
  public void minorsCannotReceiveWeightLossTarget() {
    Nutrition.Profile p = profile();
    p.age = 17;
    assertFalse(p.ready());
    assertEquals(0, p.target(), 0);
  }

  @Test
  public void underweightCannotReceiveWeightLossTarget() {
    Nutrition.Profile p = profile();
    p.weight = 48;
    assertEquals(0, p.target(), 0);
  }

  @Test
  public void photoOfLunchIsNotTheWholeDay() {
    assertNull(Nutrition.advice(profile(), meal(400, 600, true), false, false, 13).snackBudget());
  }

  @Test
  public void unconfirmedPhotoNeverUnlocksSnackBudget() {
    assertNull(Nutrition.advice(profile(), meal(400, 600, false), true, true, 23).snackBudget());
  }

  @Test
  public void unknownFoodBlocksStrongAdvice() {
    Nutrition.Total t = meal(400, 600, true);
    t.add(0, 0, 0, 0, 0, 0, 3, false, false);
    assertNull(Nutrition.advice(profile(), t, true, true, 23).snackBudget());
  }

  @Test
  public void futureDinnerIsReserved() {
    Nutrition.Profile p = profile();
    Nutrition.Total t = meal(500, 700, true);
    var a = Nutrition.advice(p, t, true, false, 13);
    assertTrue(a.body().contains("预留"));
    assertNotNull(a.snackBudget());
    assertTrue(a.snackBudget() <= 250);
  }

  @Test
  public void lowCompleteDayEncouragesFood() {
    var a = Nutrition.advice(profile(), meal(400, 600, true), true, true, 23);
    assertTrue(a.title().contains("偏低"));
    assertTrue(a.body().contains("正常补充"));
  }

  @Test
  public void highIntakeNeverRecommendsCompensatoryFasting() {
    var a = Nutrition.advice(profile(), meal(2400, 2700, true), true, true, 23);
    assertTrue(a.body().contains("无需补偿性挨饿"));
    assertNull(a.snackBudget());
  }

  @Test
  public void normalWeightDefaultsToMaintenance() {
    Nutrition.Profile p = profile();
    p.weight = 65;
    assertEquals(Math.round(Math.max(1400, p.maintenance()) / 50) * 50, p.target(), .01);
  }

  @Test
  public void calorieDeficitIsConservative() {
    Nutrition.Profile p = profile();
    assertEquals(Math.round(Math.max(1400, p.maintenance() - 250) / 50) * 50, p.target(), .01);
  }

  @Test
  public void additiveRangesPreserveUncertainty() {
    Nutrition.Total t = meal(400, 600, true);
    t.add(200, 150, 260, 6, 30, 6, 3, true, true);
    assertEquals(550, t.low, 0);
    assertEquals(860, t.high, 0);
    assertEquals(2, t.records);
  }
}
