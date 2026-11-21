"""Zahlenraten: Errate eine Zahl zwischen 1 und 100."""
import random

zahl = random.randint(1, 100)
versuche = 0
print("Ich denke an eine Zahl zwischen 1 und 100. Rate mal!")

while True:
    eingabe = input("Dein Tipp: ")
    if not eingabe.isdigit():
        print("Bitte eine ganze Zahl eingeben.")
        continue
    tipp = int(eingabe)
    versuche += 1
    if tipp < zahl:
        print("Zu klein!")
    elif tipp > zahl:
        print("Zu groß!")
    else:
        print(f"Richtig! Du hast {versuche} Versuche gebraucht.")
        break
