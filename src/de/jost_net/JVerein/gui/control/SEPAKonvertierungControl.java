/**********************************************************************
 * $Source$
 * $Revision$
 * $Date$
 * $Author$
 *
 * Copyright (c) by Heiner Jostkleigrewe
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,  but WITHOUT ANY WARRANTY; without 
 *  even the implied warranty of  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See 
 *  the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If not, 
 * see <http://www.gnu.org/licenses/>.
 * 
 * heiner@jverein.de
 * www.jverein.de
 **********************************************************************/
package de.jost_net.JVerein.gui.control;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.TableItem;
import org.supercsv.io.CsvListReader;
import org.supercsv.io.ICsvListReader;
import org.supercsv.prefs.CsvPreference;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.io.IBANUpdate;
import de.jost_net.JVerein.io.IBankverbindung;
import de.jost_net.JVerein.io.Adressbuch.Adressaufbereitung;
import de.jost_net.JVerein.rmi.Einstellung;
import de.jost_net.JVerein.rmi.Kursteilnehmer;
import de.jost_net.JVerein.rmi.Mitglied;
import de.jost_net.OBanToo.SEPA.BIC;
import de.jost_net.OBanToo.SEPA.IBAN;
import de.jost_net.OBanToo.SEPA.SEPAException;
import de.willuhn.datasource.GenericIterator;
import de.willuhn.datasource.GenericObject;
import de.willuhn.datasource.pseudo.PseudoIterator;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.gui.AbstractControl;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.Action;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.gui.Part;
import de.willuhn.jameica.gui.formatter.Formatter;
import de.willuhn.jameica.gui.formatter.TableFormatter;
import de.willuhn.jameica.gui.parts.Button;
import de.willuhn.jameica.gui.parts.TablePart;
import de.willuhn.jameica.gui.util.Color;
import de.willuhn.jameica.system.Settings;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

public class SEPAKonvertierungControl extends AbstractControl
{

  private Settings settings;

  private ArrayList<IBANUpdate> zeile = new ArrayList<IBANUpdate>();

  private TablePart ibanupdateList;

  public SEPAKonvertierungControl(AbstractView view)
  {
    super(view);
    settings = new de.willuhn.jameica.system.Settings(this.getClass());
    settings.setStoreWhenRead(true);
  }

  public Button getButtonExtExport()
  {
    Button b = new Button("IBANHIN-Datei ausgeben", new Action()
    {
      @Override
      public void handleAction(Object context)
      {
        settings = new Settings(this.getClass());
        settings.setStoreWhenRead(true);

        FileDialog fd = new FileDialog(GUI.getShell(), SWT.SAVE);
        String path = settings.getString("lastdir",
            System.getProperty("user.home"));
        if (path != null && path.length() > 0)
        {
          fd.setFilterPath(path);
        }
        fd.setFileName("IBANHIN.csv");
        fd.setText("Datei zur externen SEPA-Konvertierung");
        fd.setFilterExtensions(new String[] { "*.CSV" });
        fd.setFilterNames(new String[] { "Semikolon-separierte Datei" });

        String f = fd.open();
        try
        {
          File expdatei = new File(f);
          settings.setAttribute("lastdir", expdatei.getParent());
          exportiere(expdatei);
          GUI.getStatusBar().setSuccessText("Datei erstellt");
        }
        catch (RemoteException e)
        {
          Logger.error("Fehler", e);
        }
        catch (IOException e)
        {
          Logger.error("Fehler", e);
        }
      }
    });
    return b;
  }

  public Button getButtonExtImport()
  {
    Button b = new Button("IBANRUECK einlesen", new Action()
    {

      @Override
      public void handleAction(Object context) throws ApplicationException
      {
        settings = new Settings(this.getClass());
        settings.setStoreWhenRead(true);

        FileDialog fd = new FileDialog(GUI.getShell(), SWT.OPEN);
        String path = settings.getString("lastdir",
            System.getProperty("user.home"));
        if (path != null && path.length() > 0)
        {
          fd.setFilterPath(path);
        }

        fd.setText("IBANRUECK-Datei der externen SEPA-Konvertierung");
        fd.setFileName("IBANRUECK");
        String f = fd.open();
        try
        {
          File ibanrueck = new File(f);
          settings.setAttribute("lastdir", ibanrueck.getParent());
          importiere(ibanrueck);
          ibanupdateList.removeAll();
          for (IBANUpdate iu : zeile)
          {
            ibanupdateList.addItem(iu);
          }
        }
        catch (RemoteException e)
        {
          Logger.error("Fehler", e);
        }
        catch (IOException e)
        {
          Logger.error("Fehler", e);
        }
      }
    });
    return b;
  }

  public Button getButtonInterneKonvertierung()
  {
    Button b = new Button("Starten", new Action()
    {

      @Override
      public void handleAction(Object context) throws ApplicationException
      {
        konvertiere();
        ibanupdateList.removeAll();
        for (IBANUpdate iu : zeile)
        {
          try
          {
            ibanupdateList.addItem(iu);
          }
          catch (RemoteException e)
          {
            Logger.error("Fehler", e);
          }
        }
      }
    });
    return b;
  }

  public void konvertiere()
  {
    zeile = new ArrayList<IBANUpdate>();
    try
    {
      // Einstellungen
      DBIterator it = Einstellungen.getDBService()
          .createList(Einstellung.class);
      while (it.hasNext())
      {
        Einstellung einstellung = (Einstellung) it.next();
        if (einstellung != null)
        {
          IBANUpdate iu = new IBANUpdate(einstellung.getID(),
              einstellung.getName() + einstellung.getNameLang(),
              einstellung.getBlz(), einstellung.getKonto(), null, null, null);
          konvertiere(einstellung, iu);
          Einstellungen.setEinstellung(einstellung);
        }
      }
      // Mitglieder
      it = Einstellungen.getDBService().createList(Mitglied.class);
      it.addFilter("blz is not null and length(blz)>0 and konto is not null and length(konto)>0");
      while (it.hasNext())
      {
        Mitglied m = (Mitglied) it.next();
        Mitglied m2 = (Mitglied) Einstellungen.getDBService().createObject(
            Mitglied.class, m.getID());
        IBANUpdate iu = new IBANUpdate(m2.getID(),
            Adressaufbereitung.getNameVorname(m2), m2.getBlz(), m2.getKonto(),
            null, null, null);
        konvertiere(m2, iu);
      }
      // Kursteilnehmer
      it = Einstellungen.getDBService().createList(Kursteilnehmer.class);
      it.addFilter("blz is not null and length(blz)>0 and konto is not null and length(konto)>0");
      while (it.hasNext())
      {
        Kursteilnehmer k = (Kursteilnehmer) it.next();
        Kursteilnehmer k2 = (Kursteilnehmer) Einstellungen.getDBService()
            .createObject(Kursteilnehmer.class, k.getID());
        IBANUpdate iu = new IBANUpdate(k2.getID(),
            Adressaufbereitung.getNameVorname(k2), k2.getBlz(), k2.getKonto(),
            null, null, null);
        konvertiere(k2, iu);

      }
    }
    catch (RemoteException e)
    {
      Logger.error("Error", e);
    }
  }

  private void konvertiere(IBankverbindung obj, IBANUpdate iu)
  {
    zeile.add(iu);
    try
    {
      IBAN i = new IBAN(obj.getKonto(), obj.getBlz(), Einstellungen
          .getEinstellung().getDefaultLand());
      obj.setIban(i.getIBAN());
      iu.setIban(i.getIBAN());
      BIC bi = new BIC(obj.getKonto(), obj.getBlz(), Einstellungen
          .getEinstellung().getDefaultLand());
      obj.setBic(bi.getBIC());
      iu.setBic(bi.getBIC());
      obj.store();
      iu.setStatus("00");
    }
    catch (RemoteException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    catch (SEPAException e)
    {
      switch (e.getFehler())
      {
        case BLZ_LEER:
        case BLZ_UNGUELTIG:
        case BLZ_UNGUELTIGE_LAENGE:
        {
          iu.setStatus("10");
          break;
        }
        case IBANREGEL_NICHT_IMPLEMENTIERT:
        case UNGUELTIGES_LAND:
        {
          iu.setStatus("50");
          break;
        }
        case KONTO_LEER:
        case KONTO_PRUEFZIFFER_FALSCH:
        case KONTO_UNGUELTIGE_LAENGE:
        case KONTO_PRUEFZIFFERNREGEL_NICHT_IMPLEMENTIERT:
        {
          iu.setStatus("11");
          break;
        }
      }
    }
    catch (ApplicationException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  public Part getList() throws RemoteException
  {
    ibanupdateList = new TablePart(getIterator(), null);
    ibanupdateList.addColumn("ID", "id");
    ibanupdateList.addColumn("Name, Vorname", "name");
    ibanupdateList.addColumn("BLZ", "blz");
    ibanupdateList.addColumn(("Konto"), "konto");
    ibanupdateList.addColumn(("IBAN"), "iban");
    ibanupdateList.addColumn(("BIC"), "bic");
    ibanupdateList.addColumn(("Status"), "status", new Formatter()
    {
      @Override
      public String format(Object o)
      {
        String in = (String) o;
        if (in.equals("00"))
        {
          return "Umstellung war erfolgreich oder nicht erforderlich";
        }
        else if (in.equals("10"))
        {
          return "Bankleitzahl ung�ltig";
        }
        else if (in.equals("11"))
        {
          return "Aufbau Kontonummer falsch, z.B. auf Grund der Pr�fziffernrechnung";
        }
        else if (in.equals("13"))
        {
          return "Gemeldete Bankleitzahl ist zur L�schung vorgemerkt und wurde gegen die Nachfolgebankleitzahl ausgetauscht.";
        }
        else if (in.equals("14"))
        {
          return "IBAN wurde auf Basis einer zur L�schung vorgemerkten Bankleitzahl ermittelt. Es liegt keine Nachfolgebankleitzahl vor.";
        }
        else if (in.equals("1x"))
        {
          return "Ggf. weitere Fehler basierend auf den Angaben aus Feld 5b und Feld 5c";
        }
        else if (in.equals("20"))
        {
          return "Aufbau der IBAN alt ung�ltig";
        }
        else if (in.equals("21"))
        {
          return "Pr�fziffernrechnung der IBAN alt falsch";
        }
        else if (in.equals("22"))
        {
          return "BIC ist nicht g�ltig";
        }
        else if (in.equals("2x"))
        {
          return "Ggf. weitere Fehler basierend auf Feld 4 und Feld 5";
        }
        else if (in.equals("3x"))
        {
          return "Ggf. weitere Fehler aus kontenbezogenen Pr�fungen";
        }
        else if (in.equals("40"))
        {
          return "Konto ist kein Konto der umstellenden Stelle (gem. Feld 5 oder 5b)";
        }
        else if (in.equals("50"))
        {
          return "IBAN-Berechnung nicht m�glich";
        }
        else if (in.equals("9x"))
        {
          return "Individuelle Fehler und Hinweismeldungen die zwischen dem Einreicher und Bearbeiter der Datei vereinbart sind";
        }
        return "Unbekannter Fehlerstatus";
      }
    });
    ibanupdateList.setRememberColWidths(true);
    ibanupdateList.setFormatter(new TableFormatter()
    {
      @Override
      public void format(TableItem item)
      {
        IBANUpdate iu = (IBANUpdate) item.getData();
        if (iu.getStatusError())
        {
          item.setForeground(Color.ERROR.getSWTColor());
        }
      }
    });
    ibanupdateList.setRememberOrder(true);
    ibanupdateList.setSummary(true);
    return ibanupdateList;
  }

  private GenericIterator getIterator() throws RemoteException
  {
    GenericIterator gi = PseudoIterator.fromArray(zeile
        .toArray(new GenericObject[zeile.size()]));
    return gi;
  }

  private void exportiere(File file) throws IOException
  {
    BufferedWriter wr = new BufferedWriter(new PrintWriter(file));
    // Mitglieder
    DBIterator it = Einstellungen.getDBService().createList(Mitglied.class);
    it.addFilter("konto is not null and length(konto) > 0 and blz is not null and length(blz) > 0");
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      schreibeExportsatz(wr, "M", m.getID(), m.getBlz(), m.getKonto());
    }
    // Kursteilnehmer
    it = Einstellungen.getDBService().createList(Kursteilnehmer.class);
    it.addFilter("konto is not null and length(konto) > 0 and blz is not null and length(blz) > 0");
    while (it.hasNext())
    {
      Kursteilnehmer k = (Kursteilnehmer) it.next();
      schreibeExportsatz(wr, "K", k.getID(), k.getBlz(), k.getKonto());
    }
    // Einstellungen
    it = Einstellungen.getDBService().createList(Einstellung.class);
    it.addFilter("konto is not null and length(konto) > 0 and blz is not null and length(blz) > 0");
    while (it.hasNext())
    {
      Einstellung e = (Einstellung) it.next();
      schreibeExportsatz(wr, "E", e.getID(), e.getBlz(), e.getKonto());
    }
    wr.close();
  }

  private void schreibeExportsatz(BufferedWriter wr, String bereich, String id,
      String blz, String konto) throws IOException
  {
    wr.write("\"DE\";;");
    wr.write(bereich);
    wr.write(id);
    wr.write(";;;");
    wr.write(blz);
    wr.write(";");
    wr.write(konto);
    wr.write(";;;;;");
    wr.newLine();
  }

  private void importiere(File file) throws IOException, ApplicationException
  {
    zeile = new ArrayList<IBANUpdate>();
    ICsvListReader r = new CsvListReader(new FileReader(file),
        CsvPreference.EXCEL_NORTH_EUROPE_PREFERENCE);
    r.getCSVHeader(false);
    List<String> line = null;
    while ((line = r.read()) != null)
    {
      if (line.get(2).startsWith("M"))
      {
        Mitglied m = (Mitglied) Einstellungen.getDBService().createObject(
            Mitglied.class, line.get(2).substring(1));
        IBANUpdate iu = new IBANUpdate(m.getID(),
            Adressaufbereitung.getNameVorname(m), m.getBlz(), m.getKonto(),
            line.get(9), line.get(8), line.get(11));
        if (line.get(11).equals("00"))
        {
          m.setIban(line.get(9));
          m.setBic(line.get(8));
          m.store();
        }
        zeile.add(iu);
      }
      if (line.get(2).startsWith("K"))
      {
        Kursteilnehmer k = (Kursteilnehmer) Einstellungen.getDBService()
            .createObject(Kursteilnehmer.class, line.get(2).substring(1));
        IBANUpdate iu = new IBANUpdate(k.getID(),
            Adressaufbereitung.getNameVorname(k), k.getBlz(), k.getKonto(),
            line.get(9), line.get(8), line.get(11));
        if (line.get(11).equals("00"))
        {
          k.setIban(line.get(9));
          k.setBic(line.get(8));
          k.store();
        }
        zeile.add(iu);
      }
      if (line.get(2).startsWith("E"))
      {
        Einstellung e = (Einstellung) Einstellungen.getDBService()
            .createObject(Einstellung.class, line.get(2).substring(1));
        IBANUpdate iu = new IBANUpdate(e.getID(),
            e.getName() + e.getNameLang(), e.getBlz(), e.getKonto(),
            line.get(9), line.get(8), line.get(11));
        if (line.get(11).equals("00"))
        {
          e.setIban(line.get(9));
          e.setBic(line.get(8));
          e.store();
        }
        zeile.add(iu);
      }
    }
  }
}