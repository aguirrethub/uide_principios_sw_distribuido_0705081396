import java.io.*;
/**
 * Purpose: demonstrate simple Java Fraction class with command line,
 * jdb debugging, and Ant build file.
 *
 * Ser321 Foundations of Distributed Applications
 * see http://pooh.poly.asu.edu/Ser321
 * @author Tim Lindquist Tim.Lindquist@asu.edu
 *         Software Engineering, CIDSE, IAFSE, ASU Poly
 * @version January 2020
 * @version November 2026 - Gabriel Aguirre: main ahora acepta dos argumentos
 */
public class Fraction {

   private int numerator, denominator;

   public Fraction(){
      numerator = denominator = 0;
   }

   public void print() {
    System.out.print(numerator + "/" + denominator );
   }

   public void setNumerator (int n ){
      numerator = n;
   }

   public void setDenominator (int d) {
      denominator = d;
   }

   public int getDenominator() {
      return denominator;
   }

   public int getNumerator() {
      return numerator;
   }

   public static void main (String args[]) {
      // valores por defecto, usados cuando no llegan argumentos
      int num = 1;
      int denom = 3;

      try {
         if (args.length >= 2) {
            num = Integer.parseInt(args[0]);
            denom = Integer.parseInt(args[1]);
         } else if (args.length == 1) {
            // con un solo argumento se toma como numerador
            num = Integer.parseInt(args[0]);
            denom = 1;
         }
      } catch (NumberFormatException e) {
         System.out.println("Los argumentos deben ser numeros enteros.");
         System.out.println("Uso: gradle runFrac -Pnum=3 -Pdenom=4");
         System.exit(1);
      }

      if (denom == 0) {
         System.out.println("El denominador no puede ser cero.");
         System.exit(1);
      }

      try {
         Fraction frac = new Fraction();
         frac.setNumerator(num);
         frac.setDenominator(denom);

         System.out.print("The fraction is: ");
         frac.print();
         System.out.println("");

      }catch(Exception e) {
         e.printStackTrace();
      }
   }
}
