# 🔵 AR Proto — Prototipo Realtà Aumentata Android

Prototipo Android di AR (Realtà Aumentata) basato su **ARCore** (Google) + **SceneView**.

---

## 🧠 Come funziona la magia AR

```
📷 Camera → ARCore → Rileva piani → HitResult (coordinate 3D) → Anchor → Oggetto 3D
```

1. **ARCore** analizza il feed della fotocamera frame per frame (~60fps)
2. Usa il **Visual Odometry** (fotocamera + IMU) per capire dove si trova il telefono nello spazio
3. Rileva **superfici piane** orizzontali (pavimento, tavolo, scrivania)
4. Quando tocchi lo schermo, viene calcolato un **raggio** dal punto di tocco verso il mondo 3D
5. Il punto dove il raggio interseca una superficie → **HitResult** con coordinate `(X, Y, Z)` in metri
6. Lì viene creato un **Anchor**: un punto fisso nel mondo fisico
7. L'oggetto 3D viene agganciato all'anchor → rimane fermo anche se ti muovi

---

## 📱 Funzionalità

| Funzione | Come |
|---|---|
| Piazza oggetto 3D | Tocca qualsiasi superficie rilevata |
| Vedi le coordinate | Toast con X/Y/Z in metri dopo ogni piazzamento |
| Cambia colore | Pulsante 🎨 (8 colori disponibili) |
| Cambia forma | Ogni 3 tap su 🎨 cambia forma (Sfera → Cubo → Cilindro) |
| Cancella tutto | Pulsante 🗑️ |
| Rilevamento piani | Griglia animata ARCore sui piani trovati |

---

## ⚙️ Setup Android Studio

### Requisiti
- **Android Studio** Hedgehog o più recente
- **JDK 17** (incluso con Android Studio)
- **Dispositivo fisico** con ARCore supportato (vedi lista sotto)
  > ⚠️ L'emulatore Android non supporta ARCore con la fotocamera reale!

### Come aprire il progetto
1. Apri Android Studio
2. `File → Open` → seleziona la cartella `ARProto`
3. Attendi la sincronizzazione Gradle (~3-5 minuti prima esecuzione)
4. Collega il telefono via USB con **debug USB abilitato**
5. Premi ▶️ **Run**

### Prima build
Android Studio scaricherà automaticamente:
- ARCore SDK (~15MB)
- SceneView + Filament renderer (~80MB)
- Dipendenze Kotlin/AndroidX

---

## 📦 Stack tecnologico

```
io.github.sceneview:arsceneview:2.2.1
    └── Google ARCore             (tracking, rilevamento piani, anchors)
    └── Google Filament           (renderer 3D fisicamente basato - PBR)
    └── SceneView                 (wrapper Kotlin-friendly su ARCore+Filament)
```

**Filament** è il renderer 3D di Google usato in Google Maps, Google Search 3D objects, ecc.
Supporta materiali **PBR** (Physically Based Rendering): luci, ombre, riflessi realistici.

---

## 📐 Coordinate spaziali AR

Quando piazzi un oggetto, l'app mostra le sue coordinate:

```
X → destra/sinistra  (metri)
Y → su/giù           (metri) — 0 = livello pavimento
Z → avanti/indietro  (metri, negativo = davanti a te)
```

Le coordinate sono **relative all'origine della sessione AR** (dove hai puntato il telefono
all'avvio). Non sono coordinate GPS assolute.

---

## 📋 Dispositivi supportati da ARCore

ARCore funziona su quasi tutti i flagship Android dal 2018 in poi.
Lista completa: https://developers.google.com/ar/devices

Esempi:
- Samsung Galaxy S8+ in poi
- Google Pixel 2 in poi
- OnePlus 5 in poi
- Xiaomi Mi 8 in poi

---

## 🔧 Possibili estensioni

- **Carica modelli GLB/GLTF**: aggiungi file `.glb` in `assets/models/` e usali con `ModelNode`
- **Augmented Images**: riconosci immagini reali e sovrapponi contenuti 3D
- **Cloud Anchors**: condividi la posizione degli oggetti con altri utenti in tempo reale
- **Depth API**: usa la fotocamera di profondità (dispositivi compatibili) per occlusione reale

---

## 🌐 Multiplayer — Setup Firebase (OBBLIGATORIO)

La funzione Multiplayer usa **Firebase Realtime Database** per sincronizzare i punteggi in tempo reale tra i device dei giocatori.

### Perché Firebase Realtime Database?

- Latenza ~100-200ms (ideale per giochi in real-time)
- SDK Android ufficiale Google, stabile e ben documentato
- Piano **Spark gratuito** sufficiente per migliaia di partite al mese
- Nessun server da gestire — serverless completamente

### Passi per attivare il Multiplayer

1. **Vai su** [console.firebase.google.com](https://console.firebase.google.com)
2. Crea un nuovo progetto (es. `arfoto-multiplayer`)
3. Clicca **"Aggiungi app"** → seleziona Android
4. Inserisci il package name: `com.example.arproto`
   > Se hai rinominato il package, usa il tuo package reale
5. Scarica il file `google-services.json`
6. **Sostituisci** il file placeholder in `app/google-services.json` con quello scaricato
7. Vai su **Build → Realtime Database** nel pannello Firebase
8. Clicca **"Crea database"** → scegli una regione (es. `europe-west1`)
9. Inizia in **modalità test** (per sviluppo)

### Regole di sicurezza consigliate (produzione)

Incolla queste regole in Firebase Console → Realtime Database → Regole:

```json
{
  "rules": {
    "rooms": {
      "$roomCode": {
        ".read": "auth != null || true",
        ".write": "auth != null || true",
        "players": {
          "$playerId": {
            ".write": true
          }
        },
        "scores": {
          "$playerId": {
            ".write": true
          }
        }
      }
    }
  }
}
```

> ⚠️ Per produzione: sostituisci `true` con controlli di autenticazione Firebase Auth.

### Costo Firebase

Il piano **Spark (gratuito)** include:
- 1 GB di storage
- 10 GB di trasferimento/mese
- Connessioni simultanee: 100

Per un'app con poche migliaia di utenti è abbondantemente sufficiente.
Il piano **Blaze (pay-as-you-go)** si attiva solo se superi questi limiti.

---

## ❓ Troubleshooting Multiplayer

| Problema | Soluzione |
|---|---|
| "Firebase non configurato" | Hai dimenticato di sostituire `google-services.json` |
| "Stanza non trovata" | Controlla che il codice sia corretto e la stanza non sia scaduta (max 4h) |
| Punteggi non aggiornati | Controlla la connessione internet del device |
| Build fallisce su google-services | Verifica che il package name nel json corrisponda a quello del `build.gradle` |


| Problema | Soluzione |
|---|---|
| "ARCore non installato" | Installa/aggiorna Google Play Services for AR dal Play Store |
| L'app crasha all'avvio | Controlla che il dispositivo supporti ARCore |
| Non trova superfici | Illumina bene l'ambiente, muovi lentamente il telefono |
| Oggetti che "tremano" | Normale su superfici riflettenti (vetro, pavimento lucido) |
| Build fallisce | Sincronizza Gradle, controlla la connessione internet |
