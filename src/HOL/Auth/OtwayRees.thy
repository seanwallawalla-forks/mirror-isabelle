(*  Title:      HOL/Auth/OtwayRees
    ID:         $Id$
    Author:     Lawrence C Paulson, Cambridge University Computer Laboratory
    Copyright   1996  University of Cambridge

Inductive relation "otway" for the Otway-Rees protocol.

Version that encrypts Nonce NB

From page 244 of
  Burrows, Abadi and Needham.  A Logic of Authentication.
  Proc. Royal Soc. 426 (1989)
*)

OtwayRees = Shared + 

consts  otway   :: event list set
inductive "otway"
  intrs 
         (*Initial trace is empty*)
    Nil  "[]: otway"

         (*The spy MAY say anything he CAN say.  We do not expect him to
           invent new nonces here, but he can also use NS1.  Common to
           all similar protocols.*)
    Fake "[| evs: otway;  B ~= Spy;  
             X: synth (analz (sees Spy evs)) |]
          ==> Says Spy B X  # evs : otway"

         (*Alice initiates a protocol run*)
    OR1  "[| evs: otway;  A ~= B;  B ~= Server;  Nonce NA ~: used evs |]
          ==> Says A B {|Nonce NA, Agent A, Agent B, 
                         Crypt (shrK A) {|Nonce NA, Agent A, Agent B|} |} 
                 # evs : otway"

         (*Bob's response to Alice's message.  Bob doesn't know who 
	   the sender is, hence the A' in the sender field.
           Note that NB is encrypted.*)
    OR2  "[| evs: otway;  B ~= Server;  Nonce NB ~: used evs;
             Says A' B {|Nonce NA, Agent A, Agent B, X|} : set evs |]
          ==> Says B Server 
                  {|Nonce NA, Agent A, Agent B, X, 
                    Crypt (shrK B)
                      {|Nonce NA, Nonce NB, Agent A, Agent B|}|}
                 # evs : otway"

         (*The Server receives Bob's message and checks that the three NAs
           match.  Then he sends a new session key to Bob with a packet for
           forwarding to Alice.*)
    OR3  "[| evs: otway;  B ~= Server;  Key KAB ~: used evs;
             Says B' Server 
                  {|Nonce NA, Agent A, Agent B, 
                    Crypt (shrK A) {|Nonce NA, Agent A, Agent B|}, 
                    Crypt (shrK B) {|Nonce NA, Nonce NB, Agent A, Agent B|}|}
               : set evs |]
          ==> Says Server B 
                  {|Nonce NA, 
                    Crypt (shrK A) {|Nonce NA, Key KAB|},
                    Crypt (shrK B) {|Nonce NB, Key KAB|}|}
                 # evs : otway"

         (*Bob receives the Server's (?) message and compares the Nonces with
	   those in the message he previously sent the Server.*)
    OR4  "[| evs: otway;  A ~= B;  
             Says B Server {|Nonce NA, Agent A, Agent B, X', 
                             Crypt (shrK B)
                                   {|Nonce NA, Nonce NB, Agent A, Agent B|}|}
               : set evs;
             Says S' B {|Nonce NA, X, Crypt (shrK B) {|Nonce NB, Key K|}|}
               : set evs |]
          ==> Says B A {|Nonce NA, X|} # evs : otway"

         (*This message models possible leaks of session keys.  The nonces
           identify the protocol run.*)
    Oops "[| evs: otway;  B ~= Spy;
             Says Server B {|Nonce NA, X, Crypt (shrK B) {|Nonce NB, Key K|}|}
               : set evs |]
          ==> Says B Spy {|Nonce NA, Nonce NB, Key K|} # evs : otway"

end
