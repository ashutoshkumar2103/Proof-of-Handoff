import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { supportApi } from '../api/endpoints';
import { ErrorNotice, Pager, PlanBadge, Spinner, SubscriptionStatusBadge } from '../components/ui';
import { formatDate } from '../lib/format';

/** Find a customer by Account ID, name or email. */
export function CustomersPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 0) || 0;
  const [input, setInput] = useState(q);

  const customers = useQuery({
    queryKey: ['customers', q, page],
    queryFn: () => supportApi.customers(q, page),
  });

  const search = (e: React.FormEvent) => {
    e.preventDefault();
    setParams(input.trim() ? { q: input.trim() } : {});
  };

  return (
    <div className="stack">
      <div>
        <h1>Customers</h1>
        <p className="muted">Search by Account ID (e.g. CUS-12), name or email.</p>
      </div>

      <div className="card">
        <form className="search-bar" onSubmit={search}>
          <input aria-label="Search customers" placeholder="Account ID, name or email…" value={input}
                 onChange={(e) => setInput(e.target.value)} autoFocus />
          <button className="btn btn-primary">Search</button>
          {q && <button type="button" className="btn btn-ghost" onClick={() => { setInput(''); setParams({}); }}>Clear</button>}
        </form>

        {customers.isLoading ? <Spinner /> : customers.isError ? <ErrorNotice error={customers.error} /> : (
          customers.data!.content.length === 0 ? <p className="muted center mt-3">No customers found.</p> : (
            <>
              <div className="table-wrap mt-2">
                <table>
                  <thead>
                    <tr><th>Account ID</th><th>Name</th><th>Email</th><th>Phone</th><th>Plan</th><th>Prefix</th><th>Joined</th></tr>
                  </thead>
                  <tbody>
                    {customers.data!.content.map((c) => (
                      <tr key={c.accountCode} className="clickable" onClick={() => navigate(`/customers/${c.accountCode}`)}>
                        <td className="small">{c.accountCode}</td>
                        <td><strong>{c.name}</strong></td>
                        <td>{c.email}</td>
                        <td className="small">{c.phone ?? '—'}</td>
                        <td>
                          <PlanBadge plan={c.plan} />
                          {c.plan && c.subscriptionStatus === 'INACTIVE' && <> <SubscriptionStatusBadge status="INACTIVE" /></>}
                        </td>
                        <td className="small">{c.handoffPrefix}</td>
                        <td className="small muted">{formatDate(c.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={customers.data!}
                     onPage={(n) => setParams(q ? { q, page: String(n) } : { page: String(n) })} />
            </>
          )
        )}
      </div>
    </div>
  );
}
