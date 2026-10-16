import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminOnly, ProtectedLayout } from './components/Layout.jsx';
import {
  AdminDepositPage,
  AdminIndex,
  AdminLayout,
  AdminTransactionsPage,
  AdminUsersPage,
} from './pages/AdminPages.jsx';
import { LoginPage, RegisterPage } from './pages/AuthPages.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import HistoryPage from './pages/HistoryPage.jsx';
import ProfilePage from './pages/ProfilePage.jsx';
import TransferPage from './pages/TransferPage.jsx';
import WalletLookupPage from './pages/WalletLookupPage.jsx';

/** Route table: public auth pages, then everything else behind the auth guard. */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<ProtectedLayout />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/transfer" element={<TransferPage />} />
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/lookup" element={<WalletLookupPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route element={<AdminOnly />}>
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<AdminIndex />} />
            <Route path="users" element={<AdminUsersPage />} />
            <Route path="transactions" element={<AdminTransactionsPage />} />
            <Route path="deposit" element={<AdminDepositPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
