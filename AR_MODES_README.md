# 🐣 Easter Egg Hunt AR — Guida modalità AR

## Modalità disponibili

| Modalità | Dove funziona | Setup richiesto |
|---|---|---|
| 📐 **Standard** | Solo piani piatti (pavimento, tavoli) | Nessuno |
| 🔬 **Depth AR** | Qualsiasi superficie (spigoli, mensole, sotto mobili) | Nessuno — default |
| 🏠 **Room Scan** | Intera stanza mappata + Cloud Anchors persistenti | API Key Google Cloud |

---

## 📐 Standard
Comportamento originale. ARCore rileva solo piani orizzontali e verticali espliciti.
Adatto a device più datati o ambienti molto illuminati con pavimenti con texture.

---

## 🔬 Depth AR (default consigliato)
Usa la **Depth API** di ARCore per rilevare qualsiasi superficie, non solo i piani piani.

**Cosa cambia:**
- Puoi toccare spigoli di mobili, bordi di mensole, angoli
- Il hit-test accetta sia `Plane` che `DepthPoint` (qualunque punto con profondità nota)
- Su device **senza sensore ToF fisico** (es. Pixel 6, Samsung A series) ARCore usa
  **ML Depth Estimation** — funziona ugualmente, leggermente meno preciso

**Non richiede nessuna configurazione aggiuntiva.**

---

## 🏠 Room Scan + Cloud Anchors

### Cosa fa
1. All'avvio il genitore **cammina per la stanza ~30 secondi** puntando la fotocamera
   verso pavimento, pareti, mobili → ARCore costruisce una mappa 3D dell'ambiente
2. Terminata la scansione il genitore piazza cassaforte e uova **ovunque** — anche
   dentro la credenza (aprila e piazza l'uovo), sotto al divano, dietro la porta
3. Ogni uovo viene **hostato su Google Cloud ARCore** → riceve un `cloudAnchorId`
4. In modalità multiplayer gli ID vengono salvati su Firebase → i bambini li risolvono
   automaticamente e vedono le uova esattamente dove il genitore le ha nascoste

### Setup obbligatorio (una tantum)

#### 1. Abilita ARCore API su Google Cloud Console
```
https://console.cloud.google.com
→ Seleziona il tuo progetto Firebase
→ "API e servizi" → "Libreria"
→ Cerca "ARCore API" → Abilita
```

#### 2. Crea una API Key
```
"API e servizi" → "Credenziali"
→ "+ Crea credenziali" → "Chiave API"
→ (Opzionale ma consigliato) Limita la chiave alle app Android
```

#### 3. Inserisci la chiave in AndroidManifest.xml
```xml
<meta-data
    android:name="com.google.android.ar.api_key"
    android:value="LA_TUA_API_KEY_QUI"/>
```

### Senza API Key
Le modalità **Standard** e **Depth AR** funzionano perfettamente senza API Key.
Solo la modalità Room Scan darà errore `ERROR_NOT_AUTHORIZED` al momento dell'hosting
degli anchor — l'uovo viene comunque piazzato localmente ma non persiste tra sessioni.

### Limiti Cloud Anchors (piano gratuito Google)
- TTL anchor: **1 giorno** (configurabile in `CloudAnchorManager.ANCHOR_TTL_DAYS`)
- Quota: 1000 anchor/giorno (più che sufficiente per uso personale)
- L'ambiente deve essere sufficientemente illuminato e ricco di texture per la localizzazione

---

## Struttura file modificati

```
app/src/main/java/com/intelligame/easteregghuntar/
├── CloudAnchorManager.kt        ← NUOVO: gestisce hosting/resolving
├── RoomScanManager.kt           ← NUOVO: traccia progresso scansione
├── MainActivity.kt              ← MODIFICATO: Depth hit-test, scan flow, cloud anchors
├── EggSetupModeActivity.kt      ← MODIFICATO: selettore modalità AR
├── SettingsActivity.kt          ← MODIFICATO: impostazione AR di default
└── GameDataManager.kt           ← MODIFICATO: getArMode/setArMode

app/src/main/res/layout/
└── activity_main.xml            ← MODIFICATO: overlay scansione stanza

app/src/main/AndroidManifest.xml ← MODIFICATO: depth feature + API key placeholder
```
