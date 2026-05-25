import { Container, Stack, Typography } from "@mui/material";

// Placeholder shell. Real routing (events list, event detail, join, …)
// is wired up once the sync/crypto/domain layers are ported.
export function App() {
  return (
    <Container maxWidth="sm" sx={{ py: 4 }}>
      <Stack spacing={2}>
        <Typography variant="h4" component="h1">
          FairShare
        </Typography>
        <Typography variant="body1" color="text.secondary">
          Webapp en cours de construction. Cette page sera remplacée par
          la liste des événements une fois le pairing implémenté.
        </Typography>
      </Stack>
    </Container>
  );
}
