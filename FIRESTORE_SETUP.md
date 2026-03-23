# Easter Egg Hunt AR — Firestore Setup

## Struttura Firestore (gratuita, persistente per sempre)

```
players/{playerId}           → profilo giocatore (XP, livello, potere)
leaderboard/{playerId}       → snapshot classifica veloce
gyms/{gymId}                 → palestre permanenti
gym_trainings/{playerId_gymId} → log allenamenti (cooldown)
world_eggs/{eggId}           → uova mondo aperto (TTL automatico)
outdoor_rooms/{code}         → stanze multiplayer outdoor
```

## Regole di sicurezza Firestore

Copia queste regole in Firebase Console → Firestore → Rules:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Profili giocatori: chiunque legge, solo il proprietario scrive
    match /players/{playerId} {
      allow read: if true;
      allow write: if request.auth == null; // app senza auth → accesso libero
    }

    // Leaderboard: solo lettura pubblica
    match /leaderboard/{playerId} {
      allow read: if true;
      allow write: if true;  // l'app aggiorna dopo ogni catch
    }

    // Palestre: lettura pubblica, scrittura solo da app
    match /gyms/{gymId} {
      allow read: if true;
      allow write: if true;
    }

    // Allenamenti: lettura/scrittura da app
    match /gym_trainings/{trainId} {
      allow read, write: if true;
    }

    // Uova mondo: lettura pubblica, scrittura controllata (transazione)
    match /world_eggs/{eggId} {
      allow read: if true;
      allow write: if true;
    }

    // Stanze outdoor multiplayer
    match /outdoor_rooms/{code} {
      allow read, write: if true;
    }
  }
}
```

**Nota:** In produzione, aggiungi Firebase Authentication per regole più restrittive.
Per ora le regole permissive sono ok per il lancio in beta.

## Indici compositi necessari

Vai su Firebase Console → Firestore → Indici e crea:

1. Collezione: `world_eggs`
   - lat: Ascending
   - caught: Ascending
   - (usato da EggSpawnManager per query geografica)

2. Collezione: `leaderboard`
   - power: Descending
   - (usato per classifica mondiale)

3. Collezione: `gyms`
   - lat: Ascending
   - (usato da GymManager)

Firebase crea automaticamente altri indici quando vede le query la prima volta
(mostra un link nel Logcat → clicca per creare).

## Piano Firestore gratuito (Spark)

| Risorsa | Limite gratuito | Consumo stimato per 100 utenti/giorno |
|---------|----------------|---------------------------------------|
| Storage | 1 GB | ~5 MB (profili + uova) |
| Letture  | 50.000/giorno | ~15.000 |
| Scritture| 20.000/giorno | ~3.000 |
| Eliminaz.| 20.000/giorno | ~1.000 |

**Conclusione: Firebase Spark è completamente sufficiente per il lancio su PlayStore
fino a ~500-1000 utenti attivi/giorno. Nessun costo.**

## Utilizzo dei 3 server Ubuntu (futuro)

Se il gioco cresce oltre i limiti gratuiti Firebase:
- **Server 1**: Node.js + Express + PostgreSQL (game logic server-side)
- **Server 2**: Redis (cache leaderboard, sessioni)
- **Server 3**: Nginx reverse proxy + SSL + backup

Per ora NON necessari. Firebase gestisce tutto.
