package org.gslab.Tool;

import java.util.ArrayList;

public class FuzzyController {

    public static double getPriorty(
            double Priority,
            double ErrorValue,
            double deErrorValue,
            double ErrorValueRange,
            double deErrorValueRange,
            double Delta) {
        double Priority_New = 0.0;
        double Range_Pos = 0.0, Range_Neg = 0.0, deRange_Pos = 0.0, deRange_Neg = 0.0;
        double Value_M = 0.0, Value_S = 0.0, Value_L = 0.0;
        double mu_P = 0.0, mu_N = 0.0, mu_deP = 0.0, mu_deN = 0.0, mu_Total = 0.0;
        ArrayList<Double> mu_List = new ArrayList<>();

        // ----- Set Domain Range -----//
        Range_Pos = ErrorValueRange;
        Range_Neg = -Range_Pos;
        deRange_Pos = deErrorValueRange;
        deRange_Neg = -deRange_Pos;
        // ----------------------------//

        // ----- Count Fuzzy Singletons -----//
        Value_M = Priority;
        Value_S = Priority + Delta;
        Value_L = Priority - Delta;
        // ----------------------------------//

        // ----- Count mu_N -----//
        if (ErrorValue >= 0) {
            mu_N = 0;
        } else if (ErrorValue <= Range_Neg) {
            mu_N = 1;
        } else if (ErrorValue > Range_Neg & ErrorValue < 0) {
            mu_N = ErrorValue / Range_Neg;
        }
        // ----------------------//

        // ----- Count mu_P -----//
        if (ErrorValue >= Range_Pos) {
            mu_P = 1;
        } else if (ErrorValue <= 0) {
            mu_P = 0;
        } else if (ErrorValue < Range_Pos & ErrorValue > 0) {
            mu_P = ErrorValue / Range_Pos;
        }
        // ----------------------//

        // ----- Count mu_deN -----//
        if (deErrorValue >= deRange_Pos) {
            mu_deN = 0;
        } else if (deErrorValue <= deRange_Neg) {
            mu_deN = 1;
        } else if (deErrorValue < Range_Pos & deErrorValue > deRange_Neg) {
            mu_deN = (deErrorValue - deRange_Pos) / (deRange_Neg * 2);
        }
        // ------------------------------//

        // ----- Count mu_deP -----//
        if (deErrorValue >= deRange_Pos) {
            mu_deP = 1;
        } else if (deErrorValue <= deRange_Neg) {
            mu_deP = 0;
        } else if (deErrorValue < Range_Pos & deErrorValue > deRange_Neg) {
            mu_deP = (deErrorValue - deRange_Neg) / (deRange_Pos * 2);
        }
        // ------------------------------//

        // ----- Count mu_1, mu_2, mu_3, mu_4 -----//
        mu_List.add(Math.min(mu_P, mu_deP));
        mu_List.add(Math.min(mu_P, mu_deN));
        mu_List.add(Math.min(mu_N, mu_deP));
        mu_List.add(Math.min(mu_N, mu_deN));
        // ----------------------------------------//

        mu_Total = mu_List.get(0) + mu_List.get(1) + mu_List.get(2) + mu_List.get(3);
        Priority_New = (mu_List.get(0) * Value_S
                        + mu_List.get(1) * Value_M
                        + mu_List.get(2) * Value_M
                        + mu_List.get(3) * Value_L)
                / mu_Total;

        if (Priority_New < 0 | Double.isNaN(Priority_New)) {
            Priority_New = 0;
        }
        return Priority_New;
    }
}
