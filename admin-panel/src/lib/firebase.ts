import { initializeApp, getApps, getApp } from "firebase/app";
import { getAuth, connectAuthEmulator } from "firebase/auth";
import { getFirestore, connectFirestoreEmulator } from "firebase/firestore";
import { getFunctions, connectFunctionsEmulator } from "firebase/functions";

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY || "AIzaSyAq4opYsP9efsMFnf-Vuea_4sPO2Bsre14",
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN || "max-ai-assistant-5ffda.firebaseapp.com",
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID || "max-ai-assistant-5ffda",
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET || "max-ai-assistant-5ffda.firebasestorage.app",
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID || "789767250832",
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID || "1:789767250832:web:aedb95293750f6edd74915"
};

const app = !getApps().length ? initializeApp(firebaseConfig) : getApp();
const auth = getAuth(app);
const firestore = getFirestore(app);
const functions = getFunctions(app);

// Connect to local emulator if configured in environment
if (typeof window !== "undefined" && process.env.NEXT_PUBLIC_USE_EMULATORS === "true") {
  try {
    connectAuthEmulator(auth, "http://127.0.0.1:9099");
    connectFirestoreEmulator(firestore, "127.0.0.1", 18080);
    connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  } catch {
    // Emulator already connected
  }
}

export { app, auth, firestore, functions };
